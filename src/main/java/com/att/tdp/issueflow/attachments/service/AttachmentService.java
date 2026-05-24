package com.att.tdp.issueflow.attachments.service;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import com.att.tdp.issueflow.attachments.repository.AttachmentRepository;
import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.auth.security.CurrentUserProvider;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment upload / download / delete (spec 11 §1–§6).
 *
 * <p><b>Order of validation in {@link #upload}:</b>
 * <ol>
 *   <li>Ticket exists + not soft-deleted ({@code tickets.findById()}
 *       respects {@code @SQLRestriction}) → 404 {@code TICKET_NOT_FOUND}
 *       if either is false.</li>
 *   <li>File is present + non-empty → 400 {@code VALIDATION_FAILED}.</li>
 *   <li>Size re-check ({@code file.getSize() &lt;= 10 MB}) → 413
 *       {@code ATTACHMENT_TOO_LARGE}. Spec §2: "Re-check in service in
 *       case the limit changes." The framework's
 *       {@code MaxUploadSizeExceededException} → generic
 *       {@code PAYLOAD_TOO_LARGE} fires for the OUTER multipart cap;
 *       this re-check emits the attachment-specific code for the same
 *       semantic.</li>
 *   <li>MIME header ∈ allow-list → 415 {@code ATTACHMENT_UNSUPPORTED_TYPE}.</li>
 *   <li>Magic-byte sniff for PNG/JPEG/PDF (skipped for text/plain —
 *       Session 12 D2) → 415 {@code ATTACHMENT_UNSUPPORTED_TYPE}.</li>
 * </ol>
 *
 * <p>The order matters for diagnostics: a missing ticket should fail
 * fast (404) before we even look at the bytes. MIME comes before magic
 * sniffing because the sniff cost is "read 8 bytes" but signals nothing
 * useful for a {@code text/plain} upload.
 *
 * <p><b>Audit (spec §6):</b> {@code log(CREATE, ATTACHMENT, attachmentId)}
 * on upload, {@code log(DELETE, ATTACHMENT, attachmentId)} on delete.
 * No diff (consistent with how {@code CREATE} rows are written
 * elsewhere — diffs are only for {@code UPDATE} in this codebase).
 *
 * <p><b>Why {@code @Transactional} class-level:</b> uploads commit the
 * blob + audit row atomically (if the audit insert fails, the blob
 * insert rolls back — no orphan files). Same idiom as every other
 * write service.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AttachmentService {

    /** Spec §1 — MIME allow-list. */
    static final Set<String> ALLOWED_MIME = Set.of(
            "image/png",
            "image/jpeg",
            "application/pdf",
            "text/plain");

    /** Spec §2 — 10 MB cap. Mirrors {@code spring.servlet.multipart.max-file-size}. */
    static final long MAX_BYTES = 10L * 1024L * 1024L;

    private final AttachmentRepository attachments;
    private final TicketRepository tickets;
    private final AuditLogService auditLog;
    private final CurrentUserProvider currentUserProvider;

    // ---- upload ------------------------------------------------------------

    public Attachment upload(Long ticketId, MultipartFile file) {
        // 1. Ticket exists + not soft-deleted. Slice 9's @SQLRestriction on
        //    Ticket means a soft-deleted parent shows up as empty here, so
        //    "ticket not found" and "ticket soft-deleted" collapse to the
        //    same 404 — the client can't tell, which is the desired behavior
        //    per ADR 0002 (soft-deleted rows are invisible to non-admin paths).
        if (!tickets.existsById(ticketId)) {
            throw new NotFoundException(
                    ErrorCode.TICKET_NOT_FOUND,
                    "Ticket " + ticketId + " was not found.");
        }

        // 2. File present.
        if (file == null || file.isEmpty()) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED,
                    "Multipart 'file' part is required and must be non-empty.");
        }

        // 3. Size re-check (spec §2). Exception subclass doesn't determine
        //    the HTTP status here — GlobalExceptionHandler resolves status
        //    from `ex.code().defaultStatus()`, so ATTACHMENT_TOO_LARGE → 413
        //    regardless of which DomainException subclass we throw.
        if (file.getSize() > MAX_BYTES) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    "Attachment size " + file.getSize() + " bytes exceeds maximum " + MAX_BYTES + " bytes (10 MB).");
        }

        // 4. MIME header allow-list.
        String contentType = normaliseMime(file.getContentType());
        if (contentType == null || !ALLOWED_MIME.contains(contentType)) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE,
                    "Unsupported content type: '" + file.getContentType() + "'. Allowed: " + ALLOWED_MIME);
        }

        // 5. Magic-byte sniff for binary types (Session 12 D2).
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to read uploaded file: " + e.getMessage());
        }
        if (!magicBytesMatch(contentType, bytes)) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE,
                    "File magic bytes do not match declared content type '" + contentType + "'.");
        }

        Attachment a = new Attachment();
        a.setTicketId(ticketId);
        a.setFilename(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        a.setContentType(contentType);
        a.setSizeBytes(bytes.length);
        a.setData(bytes);
        a.setUploadedBy(requireUserId());
        Attachment saved = attachments.save(a);

        auditLog.log(AuditAction.CREATE, EntityType.ATTACHMENT, saved.getId());
        return saved;
    }

    // ---- download ----------------------------------------------------------

    /**
     * Loads the attachment WITH its bytes (the {@code @Lob} fetch fires
     * when the controller calls {@code att.getData()} inside this
     * transaction). Throws 404 {@code ATTACHMENT_NOT_FOUND} if missing.
     *
     * <p>NOTE: does NOT cross-check the {@code ticketId} path-variable
     * against the attachment's {@code ticketId} — the {@code attachmentId}
     * is globally unique. Mismatch behaviour: the URL
     * {@code /tickets/999/attachments/42} loads attachment 42 even if
     * 42's parent is ticket 7. Same idiom as
     * {@code /tickets/{tid}/dependencies/{bid}} (slice 8) — the surrogate
     * id is authoritative, the path is for client-side grouping.
     */
    @Transactional(readOnly = true)
    public Attachment download(Long attachmentId) {
        return attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.ATTACHMENT_NOT_FOUND,
                        "Attachment " + attachmentId + " was not found."));
    }

    // ---- delete ------------------------------------------------------------

    public void delete(Long attachmentId) {
        if (!attachments.existsById(attachmentId)) {
            throw new NotFoundException(
                    ErrorCode.ATTACHMENT_NOT_FOUND,
                    "Attachment " + attachmentId + " was not found.");
        }
        attachments.deleteById(attachmentId);
        auditLog.log(AuditAction.DELETE, EntityType.ATTACHMENT, attachmentId);
    }

    // ---- helpers -----------------------------------------------------------

    /** "text/csv; charset=UTF-8" → "text/csv"; null → null. Lowercased. */
    private static String normaliseMime(String contentType) {
        if (contentType == null) return null;
        String base = contentType.contains(";")
                ? contentType.substring(0, contentType.indexOf(';')).strip()
                : contentType.strip();
        return base.toLowerCase();
    }

    /**
     * Session 12 D2 — sniff magic bytes for declared binary types. Skip
     * for {@code text/plain} (no reliable magic; encoding sniffing would
     * be a footgun for non-UTF-8 content).
     */
    private static boolean magicBytesMatch(String declaredMime, byte[] bytes) {
        return switch (declaredMime) {
            case "image/png" -> MagicBytes.isPng(bytes);
            case "image/jpeg" -> MagicBytes.isJpeg(bytes);
            case "application/pdf" -> MagicBytes.isPdf(bytes);
            case "text/plain" -> true;
            default -> false;
        };
    }

    /**
     * Defensive — {@link CurrentUserProvider} returns empty for anonymous
     * or background-job contexts. Both should be impossible here because
     * the controller has {@code @PreAuthorize("isAuthenticated()")}, but
     * the schema requires {@code uploaded_by} NOT NULL so we fail loud if
     * the principal somehow isn't there (e.g. a future test that turns
     * off filters).
     */
    private Long requireUserId() {
        return currentUserProvider.currentUser()
                .map(p -> p.id())
                .orElseThrow(() -> new ValidationException(
                        ErrorCode.AUTH_TOKEN_INVALID,
                        "Upload requires an authenticated user."));
    }
}

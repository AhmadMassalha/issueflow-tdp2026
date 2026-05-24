package com.att.tdp.issueflow.attachments.api;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import com.att.tdp.issueflow.attachments.service.AttachmentService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for ticket attachments (spec 11).
 *
 * <p>Endpoint list (Session 12 D3):
 * <ul>
 *   <li>{@code POST   /tickets/{tid}/attachments}             — multipart upload (200 OK)</li>
 *   <li>{@code GET    /tickets/{tid}/attachments/{aid}}       — download bytes</li>
 *   <li>{@code DELETE /tickets/{tid}/attachments/{aid}}       — remove (204 No Content)</li>
 * </ul>
 *
 * <p><b>POST returns 200 OK</b> (not 201) for consistency with every
 * other create endpoint in this codebase (Session-02 D3 + Session-04 +
 * Session-05). The README locks 200 for the create path on every entity.
 *
 * <p><b>The {@code ticketId} path-variable is for client-side grouping,
 * not authoritative.</b> GET/DELETE identify the attachment by its
 * surrogate id — the {@code ticketId} in the URL is not cross-checked
 * against the attachment's actual {@code ticketId}. Same idiom as
 * slice-8 dependencies (URL {@code /tickets/{tid}/dependencies/{bid}}).
 * Document only — no behaviour change vs. spec.
 *
 * <p><b>RBAC (Session 12 D9):</b> {@code @PreAuthorize("isAuthenticated()")}
 * on all three methods. Per-uploader delete permission is a future
 * consideration documented in {@code prompts.md}.
 *
 * <p><b>Why {@code ResponseEntity<Resource>} (not {@code StreamingResponseBody})
 * for download:</b> the {@code @Lob byte[]} is fetched eagerly when
 * the service calls {@code att.getData()}, so the bytes are already in
 * memory by the time the controller wraps them — pretending to stream
 * via async dispatch would just add complexity for no benefit. A future
 * "video attachment" feature with a true {@code Blob} stream would
 * switch to {@code StreamingResponseBody}.
 */
@RestController
@RequestMapping("/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    // ---- upload ------------------------------------------------------------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public AttachmentResponse upload(
            @PathVariable Long ticketId,
            @RequestParam("file") MultipartFile file) {
        Attachment saved = service.upload(ticketId, file);
        return AttachmentResponse.from(saved);
    }

    // ---- download ----------------------------------------------------------

    @GetMapping("/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId) {
        Attachment a = service.download(attachmentId);
        ByteArrayResource body = new ByteArrayResource(a.getData());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .contentLength(a.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(a))
                .body(body);
    }

    // ---- delete ------------------------------------------------------------

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId) {
        service.delete(attachmentId);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Session 12 D4 — sanitised RFC-6266 {@code Content-Disposition}. Strips
     * control characters, path separators, and double-quotes from the
     * ASCII fallback {@code filename=}; preserves the original (URL-
     * encoded) name in {@code filename*=UTF-8''...} for clients that
     * understand the unicode-aware syntax.
     *
     * <p>Empty / null filenames fall back to {@code attachment-<id>} so
     * the header is always well-formed.
     */
    private static String contentDisposition(Attachment a) {
        String raw = a.getFilename();
        String safe = sanitiseFilename(raw, a.getId());
        String encoded = URLEncoder.encode(raw == null ? safe : raw, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
    }

    private static String sanitiseFilename(String raw, Long fallbackId) {
        if (raw == null || raw.isBlank()) {
            return "attachment-" + fallbackId;
        }
        String stripped = raw
                .replaceAll("[\\r\\n\\t]", "")          // control chars
                .replaceAll("[\\\\/]", "_")              // path separators
                .replace("\"", "")                       // header-injection vector
                .replaceAll("[\\x00-\\x1F\\x7F]", "");  // remaining controls
        if (stripped.isBlank()) {
            return "attachment-" + fallbackId;
        }
        return stripped.length() > 255 ? stripped.substring(0, 255) : stripped;
    }
}

package com.att.tdp.issueflow.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import com.att.tdp.issueflow.attachments.repository.AttachmentRepository;
import com.att.tdp.issueflow.attachments.service.AttachmentService;
import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.auth.security.CurrentUserProvider;
import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Mockito unit tests for {@link AttachmentService}: validation order,
 * MIME allow-list, magic-byte sniffing per declared type, size cap,
 * audit invariants, and the 404/415 paths.
 *
 * <p>True {@code @Lob} persistence + audit-row-actually-lands semantics
 * are owned by {@code AttachmentRepositoryJpaTest} and
 * {@code AttachmentsIntegrationTest}; this file owns the service-level
 * branching logic.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentRepository attachments;

    @Mock
    private TicketRepository tickets;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private AttachmentService service;

    private static final Long TID = 7L;
    private static final Long USER_ID = 42L;

    @BeforeEach
    void wireCurrentUser() {
        // Lenient because validation-failure tests (ticket missing, wrong MIME,
        // oversize, magic-byte mismatch, empty file) short-circuit BEFORE the
        // service touches currentUserProvider. Strict-stubbing would flag those
        // as unnecessary stubs even though they're correct for the happy path.
        Mockito.lenient().when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(new IssueFlowUserPrincipal(USER_ID, "alice", null, Role.DEVELOPER)));
    }

    // ---- happy upload ------------------------------------------------------

    @Test
    @DisplayName("Spec 11 §1 happy path: PNG upload persists row + writes CREATE/ATTACHMENT audit row")
    void upload_pngHappyPath() {
        when(tickets.existsById(TID)).thenReturn(true);
        when(attachments.save(any(Attachment.class))).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(101L);
            return a;
        });

        byte[] pngBytes = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x01, 0x02, 0x03 };
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", pngBytes);

        Attachment saved = service.upload(TID, file);

        assertThat(saved.getId()).isEqualTo(101L);
        assertThat(saved.getTicketId()).isEqualTo(TID);
        assertThat(saved.getFilename()).isEqualTo("logo.png");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getSizeBytes()).isEqualTo(pngBytes.length);
        assertThat(saved.getData()).containsExactly(pngBytes);
        assertThat(saved.getUploadedBy()).isEqualTo(USER_ID);
        verify(auditLog).log(AuditAction.CREATE, EntityType.ATTACHMENT, 101L);
    }

    @Test
    @DisplayName("Spec 11 §1: text/plain skips magic-byte sniff (no reliable signature) and uploads")
    void upload_textPlain_noMagicSniff() {
        when(tickets.existsById(TID)).thenReturn(true);
        when(attachments.save(any(Attachment.class))).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(102L);
            return a;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello world".getBytes());

        Attachment saved = service.upload(TID, file);
        assertThat(saved.getContentType()).isEqualTo("text/plain");
        assertThat(saved.getSizeBytes()).isEqualTo(11);
        verify(auditLog).log(AuditAction.CREATE, EntityType.ATTACHMENT, 102L);
    }

    @Test
    @DisplayName("Spec 11 §1: PDF magic-byte (%PDF) accepted; audit row landed")
    void upload_pdfMagicAccepted() {
        when(tickets.existsById(TID)).thenReturn(true);
        when(attachments.save(any(Attachment.class))).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(103L);
            return a;
        });

        byte[] pdfBytes = new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34 };
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", pdfBytes);

        service.upload(TID, file);
        verify(auditLog).log(AuditAction.CREATE, EntityType.ATTACHMENT, 103L);
    }

    @Test
    @DisplayName("Spec 11 §1: JPEG magic-byte (FF D8 FF) accepted")
    void upload_jpegMagicAccepted() {
        when(tickets.existsById(TID)).thenReturn(true);
        when(attachments.save(any(Attachment.class))).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(104L);
            return a;
        });

        byte[] jpgBytes = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10 };
        MockMultipartFile file = new MockMultipartFile(
                "file", "img.jpg", "image/jpeg", jpgBytes);

        Attachment saved = service.upload(TID, file);
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
    }

    // ---- ticket-not-found / soft-deleted ----------------------------------

    @Test
    @DisplayName("Spec 11 §3: missing ticket → 404 TICKET_NOT_FOUND; no save, no audit")
    void upload_ticketMissing_returns404() {
        when(tickets.existsById(TID)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png",
                new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(NotFoundException.class)
                .extracting(t -> ((NotFoundException) t).code())
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
        verify(attachments, never()).save(any());
        verify(auditLog, never()).log(any(), any(), any());
    }

    @Test
    @DisplayName("Session 12 D7: soft-deleted ticket appears missing → 404 (existsById respects @SQLRestriction)")
    void upload_softDeletedTicket_returns404() {
        // tickets.existsById uses @SQLRestriction, so a soft-deleted parent
        // returns false — same behaviour as "ticket never existed".
        when(tickets.existsById(TID)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png",
                new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(NotFoundException.class);
        verify(attachments, never()).save(any());
    }

    // ---- empty / missing file --------------------------------------------

    @Test
    @DisplayName("Empty file part → 400 VALIDATION_FAILED")
    void upload_emptyFile_returns400() {
        when(tickets.existsById(TID)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(ValidationException.class)
                .extracting(t -> ((ValidationException) t).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(attachments, never()).save(any());
    }

    // ---- size cap --------------------------------------------------------

    @Test
    @DisplayName("Spec 11 §2: oversize file (> 10 MB) → 413 ATTACHMENT_TOO_LARGE; no save")
    void upload_overSize_returns413() {
        when(tickets.existsById(TID)).thenReturn(true);

        // Build a fake oversize file via a stub returning the right size+content type.
        // MockMultipartFile.getSize() returns the array length, so we'd have to
        // allocate 10 MB+. Use a content stub instead: declare 11 MB via a
        // MockMultipartFile constructor that takes the size implicitly.
        byte[] bigBytes = new byte[(int) (10L * 1024L * 1024L + 1)];
        bigBytes[0] = (byte) 0x89;
        bigBytes[1] = 0x50; bigBytes[2] = 0x4E; bigBytes[3] = 0x47;
        bigBytes[4] = 0x0D; bigBytes[5] = 0x0A; bigBytes[6] = 0x1A; bigBytes[7] = 0x0A;
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.png", "image/png", bigBytes);

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(ValidationException.class)
                .extracting(t -> ((ValidationException) t).code())
                .isEqualTo(ErrorCode.ATTACHMENT_TOO_LARGE);
        verify(attachments, never()).save(any());
    }

    // ---- MIME allow-list -------------------------------------------------

    @Test
    @DisplayName("Spec 11 §1: disallowed MIME (application/zip) → 415 ATTACHMENT_UNSUPPORTED_TYPE")
    void upload_disallowedMime_returns415() {
        when(tickets.existsById(TID)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "thing.zip", "application/zip", new byte[] { 0x50, 0x4B, 0x03, 0x04 });

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(ValidationException.class)
                .extracting(t -> ((ValidationException) t).code())
                .isEqualTo(ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("Null content-type → 415 (defensive — same as disallowed MIME)")
    void upload_nullContentType_returns415() {
        when(tickets.existsById(TID)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "thing.png", null, new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

        assertThatThrownBy(() -> service.upload(TID, file))
                .isInstanceOf(ValidationException.class)
                .extracting(t -> ((ValidationException) t).code())
                .isEqualTo(ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("Charset suffix on Content-Type ('text/plain; charset=UTF-8') is tolerated")
    void upload_charsetSuffix_tolerated() {
        when(tickets.existsById(TID)).thenReturn(true);
        when(attachments.save(any(Attachment.class))).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setId(105L);
            return a;
        });

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain; charset=UTF-8", "hi".getBytes());

        Attachment saved = service.upload(TID, file);
        assertThat(saved.getContentType()).isEqualTo("text/plain");
    }

    // ---- magic-byte sniff -------------------------------------------------

    @Test
    @DisplayName("Session 12 D2: declared PNG with non-PNG bytes → 415 ATTACHMENT_UNSUPPORTED_TYPE")
    void upload_pngHeaderButWrongBytes_returns415() {
        when(tickets.existsById(TID)).thenReturn(true);

        byte[] notPng = "MZ\u0090\u0000\u0003".getBytes(); // looks like a PE/EXE header
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", notPng);

        ValidationException ex = (ValidationException) org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class, () -> service.upload(TID, file));
        assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
        assertThat(ex.getMessage()).contains("magic bytes");
        verify(attachments, never()).save(any());
    }

    @Test
    @DisplayName("Session 12 D2: declared JPEG with non-JPEG bytes → 415 ATTACHMENT_UNSUPPORTED_TYPE")
    void upload_jpegHeaderButWrongBytes_returns415() {
        when(tickets.existsById(TID)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", new byte[] { 0x00, 0x00, 0x00 });

        ValidationException ex = (ValidationException) org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class, () -> service.upload(TID, file));
        assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("Session 12 D2: declared PDF with non-PDF bytes → 415 ATTACHMENT_UNSUPPORTED_TYPE")
    void upload_pdfHeaderButWrongBytes_returns415() {
        when(tickets.existsById(TID)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "this is not a pdf".getBytes());

        ValidationException ex = (ValidationException) org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class, () -> service.upload(TID, file));
        assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
    }

    // ---- download --------------------------------------------------------

    @Test
    @DisplayName("download: returns entity with bytes; no audit row written (reads aren't audited)")
    void download_happy() {
        Attachment a = new Attachment();
        a.setId(101L);
        a.setData(new byte[] { 1, 2, 3 });
        when(attachments.findById(101L)).thenReturn(Optional.of(a));

        Attachment got = service.download(101L);

        assertThat(got).isSameAs(a);
        verify(auditLog, never()).log(any(), any(), any());
    }

    @Test
    @DisplayName("download: missing id → 404 ATTACHMENT_NOT_FOUND")
    void download_missing_returns404() {
        when(attachments.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(t -> ((NotFoundException) t).code())
                .isEqualTo(ErrorCode.ATTACHMENT_NOT_FOUND);
    }

    // ---- delete ----------------------------------------------------------

    @Test
    @DisplayName("delete: happy path removes row + writes DELETE/ATTACHMENT audit row with entityId")
    void delete_happy() {
        when(attachments.existsById(101L)).thenReturn(true);

        service.delete(101L);

        verify(attachments).deleteById(101L);
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(auditLog).log(eqAction(AuditAction.DELETE), eqEntity(EntityType.ATTACHMENT), idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(101L);
    }

    @Test
    @DisplayName("delete: missing id → 404 ATTACHMENT_NOT_FOUND; no audit row")
    void delete_missing_returns404() {
        when(attachments.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(t -> ((NotFoundException) t).code())
                .isEqualTo(ErrorCode.ATTACHMENT_NOT_FOUND);
        verify(attachments, never()).deleteById(any());
        verify(auditLog, never()).log(any(), any(), any());
    }

    // ---- helpers ----------------------------------------------------------

    private static AuditAction eqAction(AuditAction a) {
        return org.mockito.ArgumentMatchers.eq(a);
    }

    private static EntityType eqEntity(EntityType e) {
        return org.mockito.ArgumentMatchers.eq(e);
    }
}

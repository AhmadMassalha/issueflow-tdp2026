package com.att.tdp.issueflow.attachments;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.attachments.api.AttachmentController;
import com.att.tdp.issueflow.attachments.domain.Attachment;
import com.att.tdp.issueflow.attachments.service.AttachmentService;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer coverage for {@link AttachmentController} — multipart
 * upload, download response shape (Content-Type, Content-Length,
 * Content-Disposition with sanitised filename), DELETE 204, and the
 * 404/415 paths.
 *
 * <p>{@code addFilters = false} per the slice-3 Gotcha — the JWT chain
 * is out of scope here. Real chain enforcement is covered by
 * {@code AttachmentsIntegrationTest}.
 */
@WebMvcTest(controllers = AttachmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AttachmentControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AttachmentService service;

    private static final Long TID = 7L;
    private static final Long AID = 101L;

    // ---- upload ----------------------------------------------------------

    @Test
    @DisplayName("POST /tickets/{tid}/attachments returns 200 + AttachmentResponse metadata (no data bytes)")
    void upload_returnsMetadataOnly() throws Exception {
        when(service.upload(eq(TID), any())).thenReturn(att(AID, "logo.png", "image/png", 11));

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", "hello-world".getBytes());

        mvc.perform(multipart("/tickets/{tid}/attachments", TID).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AID))
                .andExpect(jsonPath("$.ticketId").value(TID))
                .andExpect(jsonPath("$.filename").value("logo.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(11))
                // CRITICAL: data bytes must NOT be in the response (Session 12 D5).
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("POST /tickets/{tid}/attachments without the 'file' part → 400 (Spring's MissingServletRequestPartException)")
    void upload_missingFilePart_returns400() throws Exception {
        mvc.perform(multipart("/tickets/{tid}/attachments", TID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Spec 11 §1: service throws ATTACHMENT_UNSUPPORTED_TYPE → controller returns 415")
    void upload_unsupportedMime_returns415() throws Exception {
        when(service.upload(eq(TID), any()))
                .thenThrow(new ValidationException(
                        ErrorCode.ATTACHMENT_UNSUPPORTED_TYPE,
                        "Unsupported content type: 'application/zip'."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "thing.zip", "application/zip", new byte[] { 0x50, 0x4B });

        mvc.perform(multipart("/tickets/{tid}/attachments", TID).file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_UNSUPPORTED_TYPE"));
    }

    @Test
    @DisplayName("Spec 11 §2: service throws ATTACHMENT_TOO_LARGE → controller returns 413")
    void upload_tooLarge_returns413() throws Exception {
        when(service.upload(eq(TID), any()))
                .thenThrow(new ValidationException(
                        ErrorCode.ATTACHMENT_TOO_LARGE,
                        "Attachment size exceeds maximum 10 MB."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.png", "image/png", new byte[] { 0 });

        mvc.perform(multipart("/tickets/{tid}/attachments", TID).file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
    }

    @Test
    @DisplayName("Spec 11 §3: service throws TICKET_NOT_FOUND → controller returns 404")
    void upload_ticketMissing_returns404() throws Exception {
        when(service.upload(eq(TID), any()))
                .thenThrow(new NotFoundException(
                        ErrorCode.TICKET_NOT_FOUND, "Ticket 7 was not found."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", new byte[] { 0 });

        mvc.perform(multipart("/tickets/{tid}/attachments", TID).file(file))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    // ---- download --------------------------------------------------------

    @Test
    @DisplayName("GET download returns bytes with correct Content-Type, Content-Length, Content-Disposition")
    void download_streamsBytesWithCorrectHeaders() throws Exception {
        Attachment a = att(AID, "diagram.png", "image/png", 6);
        a.setData(new byte[] { 1, 2, 3, 4, 5, 6 });
        when(service.download(AID)).thenReturn(a);

        mvc.perform(get("/tickets/{tid}/attachments/{aid}", TID, AID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Length", "6"))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"diagram.png\"")))
                .andExpect(header().string("Content-Disposition",
                        containsString("filename*=UTF-8''diagram.png")));

        // Bytes themselves on the response body:
        verify(service).download(AID);
    }

    @Test
    @DisplayName("Session 12 D4: filename containing quote/control chars is sanitised in Content-Disposition")
    void download_filenameSanitised() throws Exception {
        Attachment a = att(AID, "evil\"name\r\n.png", "image/png", 1);
        a.setData(new byte[] { 0 });
        when(service.download(AID)).thenReturn(a);

        mvc.perform(get("/tickets/{tid}/attachments/{aid}", TID, AID))
                .andExpect(status().isOk())
                // Sanitised ASCII fallback: control chars removed, quote stripped.
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"evilname\\.png\".*")));
    }

    @Test
    @DisplayName("Session 12 D4: blank filename falls back to attachment-<id>")
    void download_blankFilenameFallback() throws Exception {
        Attachment a = att(AID, "", "image/png", 1);
        a.setData(new byte[] { 0 });
        when(service.download(AID)).thenReturn(a);

        mvc.perform(get("/tickets/{tid}/attachments/{aid}", TID, AID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("filename=\"attachment-" + AID + "\"")));
    }

    @Test
    @DisplayName("GET download: missing id → 404 ATTACHMENT_NOT_FOUND")
    void download_missing_returns404() throws Exception {
        when(service.download(999L))
                .thenThrow(new NotFoundException(
                        ErrorCode.ATTACHMENT_NOT_FOUND, "Attachment 999 was not found."));

        mvc.perform(get("/tickets/{tid}/attachments/{aid}", TID, 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    // ---- delete ----------------------------------------------------------

    @Test
    @DisplayName("DELETE returns 204 No Content; delegates to service")
    void delete_returns204() throws Exception {
        mvc.perform(delete("/tickets/{tid}/attachments/{aid}", TID, AID))
                .andExpect(status().isNoContent());
        verify(service).delete(AID);
    }

    @Test
    @DisplayName("DELETE: missing id → 404 ATTACHMENT_NOT_FOUND")
    void delete_missing_returns404() throws Exception {
        doThrow(new NotFoundException(ErrorCode.ATTACHMENT_NOT_FOUND, "Attachment 999 was not found."))
                .when(service).delete(999L);

        mvc.perform(delete("/tickets/{tid}/attachments/{aid}", TID, 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    // ---- helpers ---------------------------------------------------------

    private static Attachment att(Long id, String filename, String contentType, int size) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setTicketId(TID);
        a.setFilename(filename);
        a.setContentType(contentType);
        a.setSizeBytes(size);
        a.setUploadedBy(42L);
        a.setCreatedAt(Instant.parse("2026-05-22T12:00:00Z"));
        return a;
    }
}

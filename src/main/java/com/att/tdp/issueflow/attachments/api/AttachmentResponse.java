package com.att.tdp.issueflow.attachments.api;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import java.time.Instant;

/**
 * Metadata-only projection of {@link Attachment} (Session 12 D5).
 *
 * <p>NEVER includes the {@code data} bytes — that would balloon JSON
 * responses to multi-MB even for endpoints that just need to confirm
 * a successful upload. The bytes channel is {@code GET /tickets/{tid}/attachments/{aid}}.
 *
 * <p>Returned from:
 * <ul>
 *   <li>{@code POST /tickets/{tid}/attachments} (200 OK with the created
 *       attachment's metadata — caller needs the id to download or
 *       delete later)</li>
 * </ul>
 */
public record AttachmentResponse(
        Long id,
        Long ticketId,
        String filename,
        String contentType,
        Integer sizeBytes,
        Long uploadedBy,
        Instant createdAt
) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getTicketId(),
                a.getFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                a.getUploadedBy(),
                a.getCreatedAt());
    }
}

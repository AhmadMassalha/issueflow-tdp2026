package com.att.tdp.issueflow.comments.api;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import java.time.Instant;
import java.util.List;

/**
 * Response body for every {@code /tickets/{ticketId}/comments/**} endpoint
 * AND for the {@code /users/{id}/mentions} listing (spec 09 §4 — "Each
 * item in {@code data[]} is the full comment shape including
 * {@code mentionedUsers}").
 *
 * <p><b>Session 10 D7 — breaking change from slice 6.</b> Originally
 * {@code mentionedUsers} was {@code List<Long>} (always-empty IDs). Spec 09
 * §1 mandates {@code [{ id, username, fullName }]}, so the field's element
 * type is now {@link MentionedUserSummary}. The slice-6 JavaDoc literally
 * flagged this as the most likely slice-10 change (see git history of this
 * file). Updating the type in place — rather than adding a parallel
 * {@code mentionedUsersDetail} field — keeps the API surface tight and
 * matches the spec wording exactly.
 *
 * <p>Two factory methods on purpose:
 * <ul>
 *   <li>{@link #from(Comment, List)} — call when you already have the
 *       mentions (just synced them, or batch-loaded them for a page).
 *       Pass {@code List.of()} for the rare endpoint that doesn't need
 *       them.</li>
 *   <li>{@link #fromWithoutMentions(Comment)} — alias for {@code from(c,
 *       List.of())}. Reads better at call sites that aren't a mention
 *       boundary (e.g. nothing-changed early-returns in
 *       {@code CommentService.update}).</li>
 * </ul>
 */
public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        List<MentionedUserSummary> mentionedUsers,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static CommentResponse from(Comment c, List<MentionedUserSummary> mentionedUsers) {
        return new CommentResponse(
                c.getId(),
                c.getTicketId(),
                c.getAuthorId(),
                c.getContent(),
                mentionedUsers == null ? List.of() : mentionedUsers,
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public static CommentResponse fromWithoutMentions(Comment c) {
        return from(c, List.of());
    }
}

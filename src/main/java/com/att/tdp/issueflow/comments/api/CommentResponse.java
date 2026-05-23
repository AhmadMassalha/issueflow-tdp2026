package com.att.tdp.issueflow.comments.api;

import com.att.tdp.issueflow.comments.domain.Comment;
import java.time.Instant;
import java.util.List;

/**
 * Response body for every {@code /tickets/{ticketId}/comments/**} endpoint.
 *
 * <p><b>{@code mentionedUsers} is included as an empty list now.</b> Spec 05
 * §1 explicitly says "including {@code mentionedUsers} (empty array until
 * slice 10)". Locking the shape in slice 6 means slice 10 (mentions) is
 * purely additive — clients written against slice 6 won't see a new field
 * appear out of nowhere and won't have to update parsers when the data
 * starts flowing.
 *
 * <p>The field type is {@code List<Long>} (user ids). Slice 10 may
 * eventually want a richer {@code List<UserSummary>} — if so, we'll add
 * that as a separate field then ({@code mentionedUsersDetail}?) rather
 * than mutating this one's type. Keeping the slice-10 escape hatch open.
 */
public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        List<Long> mentionedUsers,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static CommentResponse from(Comment c) {
        return new CommentResponse(
                c.getId(),
                c.getTicketId(),
                c.getAuthorId(),
                c.getContent(),
                List.of(),                       // slice 10 will populate
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}

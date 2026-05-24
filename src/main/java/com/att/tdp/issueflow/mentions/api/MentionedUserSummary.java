package com.att.tdp.issueflow.mentions.api;

/**
 * Projection of a mentioned user as it appears in {@code CommentResponse.mentionedUsers}
 * and {@code MentionResponse} (spec 09 §1, Session 10 D7).
 *
 * <p>Shape per the spec verbatim:
 * <pre>
 *   { "id": 42, "username": "alice", "fullName": "Alice Liddell" }
 * </pre>
 *
 * <p>Not a full {@code UserResponse} on purpose — those carry {@code email},
 * {@code role}, {@code createdAt} etc. that we don't want to leak through
 * the comment surface (e.g. a non-ADMIN reader of a ticket would otherwise
 * learn other users' email addresses via the comment thread). Three fields
 * is the minimum required to render "@alice" → "Alice Liddell" in the UI.
 *
 * <p>Used as a JPQL projection target ({@code select new …(u.id, u.username,
 * u.fullName) from User u …}) — record components must match constructor
 * order exactly or Hibernate throws at startup.
 */
public record MentionedUserSummary(
        Long id,
        String username,
        String fullName
) {
}

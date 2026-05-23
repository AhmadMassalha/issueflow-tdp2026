package com.att.tdp.issueflow.comments.api;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /tickets/{ticketId}/comments/{commentId}}.
 *
 * <p>Only {@code content} is editable — you cannot move a comment to a
 * different ticket or change its author. Both fields are nullable in the
 * DTO; the service decides what "null" means for each:
 * <ul>
 *   <li>{@code content == null} → unchanged. (Empty string would be a
 *       different decision — disallowed here because spec §1 marks content
 *       as required ≥1 char on create, and we don't want PATCH to silently
 *       weaken that invariant. {@code @Size(max = 5000)} still applies.)</li>
 *   <li>{@code version == null} → service throws 400 {@code VERSION_REQUIRED}
 *       per spec §3 (same pattern as Session-05 D3 / Tickets). Doing the
 *       check in the service (not as {@code @NotNull}) gives us the
 *       feature-specific code instead of the generic
 *       {@code VALIDATION_FAILED}.</li>
 * </ul>
 *
 * <p>If both fields are null (or {@code content} is null and {@code version}
 * is present but there's no other change to apply), the service still runs
 * the version check first; a no-op PATCH with the right version succeeds
 * and the response carries the same {@code version} (Hibernate doesn't bump
 * when nothing dirty). This is intentional: it lets clients use PATCH for
 * an optimistic-lock "still mine?" probe without needing a separate
 * endpoint, mirrors Session-04 D4's "trivial PATCH" reasoning, and is too
 * minor to warrant a dedicated Session-06 decision row.
 */
public record PatchCommentRequest(
        @Size(max = 5000)
        String content,

        Long version
) {}

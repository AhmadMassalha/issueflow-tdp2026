package com.att.tdp.issueflow.comments.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /tickets/{ticketId}/comments}.
 *
 * <p>{@code ticketId} comes from the URL path, not the body — so the body
 * carries only the human-authored payload. {@code authorId} is
 * intentionally absent: per Session 06 D1, it's derived server-side from
 * the JWT principal and a body-supplied value would be ignored. Omitting
 * the field from the DTO makes the contract honest.
 */
public record CreateCommentRequest(
        @NotBlank
        @Size(max = 5000)
        String content
) {}

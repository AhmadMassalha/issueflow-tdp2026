package com.att.tdp.issueflow.dependencies.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /tickets/{ticketId}/dependencies}.
 *
 * <p>Only one field — the blocker ticket id. The "blocked" ticket is the
 * one in the URL path; declaring it in the body too would invite a
 * mismatch ambiguity ("which one wins?") that the URL-only design
 * sidesteps. Spec 07 body shape: {@code {"blockedBy": <ticketId>}}.
 *
 * <p>{@code @Positive} catches the obvious 0 / negative ids at the
 * controller boundary so the service never sees them. The same-ticket
 * (self-dependency) check is a service-layer concern — the DTO can't see
 * the URL path's {@code ticketId}.
 */
public record AddDependencyRequest(
        @NotNull(message = "blockedBy is required")
        @Positive(message = "blockedBy must be a positive ticket id")
        Long blockedBy
) {
}

package com.att.tdp.issueflow.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * JSON envelope returned by every error response.
 *
 * <p>Shape (locked by {@code .cursor/rules/20-api-contract.mdc}):
 * <pre>
 * {
 *   "statusCode": 409,
 *   "error":      "Conflict",
 *   "code":       "TICKET_DONE_IS_IMMUTABLE",
 *   "message":    "Ticket 42 is DONE and cannot be modified.",
 *   "path":       "/tickets/42",
 *   "timestamp":  "2026-05-22T18:34:56.000Z",
 *   "details":    [ { "field": "status", "issue": "..." } ]
 * }
 * </pre>
 *
 * <p>{@code details} is omitted from the JSON when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int statusCode,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        List<FieldIssue> details
) {

    public static ApiError of(HttpStatus status, ErrorCode code, String message, String path) {
        return new ApiError(status.value(), status.getReasonPhrase(), code.name(), message, path, Instant.now(), null);
    }

    public static ApiError of(HttpStatus status, ErrorCode code, String message, String path, List<FieldIssue> details) {
        return new ApiError(status.value(), status.getReasonPhrase(), code.name(), message, path, Instant.now(), details);
    }

    /** A single per-field violation used inside {@link #details}. */
    public record FieldIssue(String field, String issue) {}
}

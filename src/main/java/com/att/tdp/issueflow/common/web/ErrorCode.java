package com.att.tdp.issueflow.common.web;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error identifiers used in the JSON error envelope.
 *
 * <p>Each value carries its default HTTP status so the {@code GlobalExceptionHandler}
 * can build a response without per-call status maps. Codes are upper-snake-case and
 * grouped by feature.
 *
 * <p>New codes are added per slice. The handler also exposes a small number of
 * generic codes for framework-level failures (validation, malformed JSON,
 * missing params, payload too large, etc.).
 */
public enum ErrorCode {
    // ---- Generic / framework-level ------------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CONFLICT(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    UNPROCESSABLE_ENTITY(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // ---- Auth (spec 02) -----------------------------------------------------------
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN),
    VERSION_REQUIRED(HttpStatus.BAD_REQUEST),

    // ---- User (spec 01) -----------------------------------------------------------
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    USER_DUPLICATE_USERNAME(HttpStatus.CONFLICT),
    USER_DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    USER_INVALID_ROLE(HttpStatus.BAD_REQUEST),

    // ---- Project (spec 03) --------------------------------------------------------
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROJECT_DUPLICATE_NAME(HttpStatus.CONFLICT),

    // ---- Ticket (spec 04) ---------------------------------------------------------
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND),
    TICKET_VERSION_CONFLICT(HttpStatus.CONFLICT),
    TICKET_INVALID_TRANSITION(HttpStatus.CONFLICT),
    TICKET_DONE_IS_IMMUTABLE(HttpStatus.CONFLICT),
    TICKET_HAS_OPEN_BLOCKERS(HttpStatus.CONFLICT),
    INVALID_ASSIGNEE(HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- Comment (spec 05) --------------------------------------------------------
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    COMMENT_VERSION_CONFLICT(HttpStatus.CONFLICT),
    COMMENT_FORBIDDEN(HttpStatus.FORBIDDEN),

    // ---- Dependency (spec 07) -----------------------------------------------------
    DEPENDENCY_NOT_FOUND(HttpStatus.NOT_FOUND),
    DEPENDENCY_EXISTS(HttpStatus.CONFLICT),
    DEPENDENCY_SELF(HttpStatus.UNPROCESSABLE_ENTITY),
    DEPENDENCY_DIFFERENT_PROJECT(HttpStatus.UNPROCESSABLE_ENTITY),
    DEPENDENCY_CYCLE(HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- Soft delete (spec 08) ----------------------------------------------------
    ALREADY_ACTIVE(HttpStatus.CONFLICT),

    // ---- CSV (spec 10) ------------------------------------------------------------
    CSV_UNKNOWN_COLUMN(HttpStatus.BAD_REQUEST),
    CSV_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // ---- Attachment (spec 11) -----------------------------------------------------
    ATTACHMENT_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    ATTACHMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus defaultStatus;

    ErrorCode(HttpStatus defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public HttpStatus defaultStatus() {
        return defaultStatus;
    }
}

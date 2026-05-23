package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ErrorCode;

/**
 * Optimistic-locking conflict mapped from {@code ObjectOptimisticLockingFailureException}
 * to a typed domain exception so service code can re-throw with a feature-specific code
 * (e.g. {@link ErrorCode#TICKET_VERSION_CONFLICT}, {@link ErrorCode#COMMENT_VERSION_CONFLICT}).
 *
 * <p>HTTP 409.
 */
public class VersionConflictException extends DomainException {
    public VersionConflictException(ErrorCode code, String message) {
        super(code, message);
    }
}

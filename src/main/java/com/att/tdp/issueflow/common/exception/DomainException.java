package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import java.util.List;

/**
 * Base for every exception the application throws on purpose.
 *
 * <p>Carries an {@link ErrorCode} so {@code GlobalExceptionHandler} can build the
 * {@link ApiError} envelope without instance-of chains.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.FieldIssue> details;

    protected DomainException(ErrorCode code, String message) {
        this(code, message, null);
    }

    protected DomainException(ErrorCode code, String message, List<ApiError.FieldIssue> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.FieldIssue> details() {
        return details;
    }
}

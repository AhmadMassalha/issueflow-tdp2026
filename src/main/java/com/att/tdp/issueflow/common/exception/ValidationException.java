package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import java.util.List;

/**
 * Semantic validation failure that DTO/Bean Validation cannot catch on its own →
 * HTTP 422 by default (callers may use a 400-class {@link ErrorCode} when more
 * appropriate, e.g. {@code VERSION_REQUIRED}).
 */
public class ValidationException extends DomainException {

    public ValidationException(ErrorCode code, String message) {
        super(code, message);
    }

    public ValidationException(ErrorCode code, String message, List<ApiError.FieldIssue> details) {
        super(code, message, details);
    }
}

package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ErrorCode;

/** Authenticated user lacks the role/ownership required → HTTP 403. */
public class ForbiddenException extends DomainException {
    public ForbiddenException(ErrorCode code, String message) {
        super(code, message);
    }
}

package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ErrorCode;

/** Resource lookup failure → HTTP 404. */
public class NotFoundException extends DomainException {
    public NotFoundException(ErrorCode code, String message) {
        super(code, message);
    }
}

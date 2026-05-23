package com.att.tdp.issueflow.common.exception;

import com.att.tdp.issueflow.common.web.ErrorCode;

/**
 * Business-rule conflict → HTTP 409.
 *
 * <p>Used for: duplicate unique-constraint hits, FSM violations, DONE-immutability,
 * unresolved blockers, restore-of-active records, optimistic-lock failures mapped
 * by service code.
 */
public class ConflictException extends DomainException {
    public ConflictException(ErrorCode code, String message) {
        super(code, message);
    }
}

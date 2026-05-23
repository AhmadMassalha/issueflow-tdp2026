package com.att.tdp.issueflow.common.enums;

/**
 * Action recorded in the audit log (spec 06).
 *
 * <p>{@link #AUTO_ASSIGN} and {@link #AUTO_ESCALATE} are written by background
 * jobs with {@link Actor#SYSTEM}. All others are written with {@link Actor#USER}.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE,
    AUTO_ASSIGN,
    AUTO_ESCALATE,
    LOGIN,
    LOGOUT
}

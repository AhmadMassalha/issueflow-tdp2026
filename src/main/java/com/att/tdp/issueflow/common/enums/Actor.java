package com.att.tdp.issueflow.common.enums;

/**
 * Origin of an audit-log entry.
 *
 * <p>{@link #USER} = a logged-in user triggered the action via the API.
 * {@link #SYSTEM} = a background job (auto-assignment, auto-escalation, etc.)
 * triggered the action with no user in the request context.
 */
public enum Actor {
    USER,
    SYSTEM
}

package com.att.tdp.issueflow.common.enums;

/**
 * Domain entities referenced by an audit-log row (spec 06).
 *
 * <p>Declared as a closed set so {@code @RequestParam EntityType} bindings on
 * {@code /audit-logs} reject unknown values with 400 {@code VALIDATION_FAILED}
 * automatically (via the existing {@code handleTypeMismatch} arm of
 * {@link com.att.tdp.issueflow.common.web.GlobalExceptionHandler}).
 *
 * <p>{@link #ATTACHMENT} and {@link #DEPENDENCY} are pre-seeded for slices 8
 * and 12 — declaring them now means future slices add audit hooks without
 * touching this enum.
 */
public enum EntityType {
    TICKET,
    PROJECT,
    USER,
    COMMENT,
    ATTACHMENT,
    DEPENDENCY
}

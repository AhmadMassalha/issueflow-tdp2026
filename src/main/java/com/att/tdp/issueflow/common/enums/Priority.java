package com.att.tdp.issueflow.common.enums;

/**
 * Ticket priority. Ordered LOW &lt; MEDIUM &lt; HIGH &lt; CRITICAL.
 *
 * <p>Used by spec 13 (escalation) which promotes overdue tickets one level
 * at a time until they reach {@link #CRITICAL}.
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * @return the next-higher priority, or empty if this is already {@link #CRITICAL}.
     */
    public java.util.Optional<Priority> escalate() {
        return switch (this) {
            case LOW -> java.util.Optional.of(MEDIUM);
            case MEDIUM -> java.util.Optional.of(HIGH);
            case HIGH -> java.util.Optional.of(CRITICAL);
            case CRITICAL -> java.util.Optional.empty();
        };
    }
}

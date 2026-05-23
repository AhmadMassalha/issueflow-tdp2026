package com.att.tdp.issueflow.common.enums;

/**
 * Ticket lifecycle states.
 *
 * <p>Spec 04 §7 restricts movement to one-step forward only:
 * TODO → IN_PROGRESS → IN_REVIEW → DONE. Backward or skip transitions
 * are rejected by {@code TicketService#transition} with HTTP 409.
 */
public enum TicketStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE;

    /**
     * @return the next legal state in the lifecycle, or empty if this is the terminal state.
     */
    public java.util.Optional<TicketStatus> next() {
        return switch (this) {
            case TODO -> java.util.Optional.of(IN_PROGRESS);
            case IN_PROGRESS -> java.util.Optional.of(IN_REVIEW);
            case IN_REVIEW -> java.util.Optional.of(DONE);
            case DONE -> java.util.Optional.empty();
        };
    }

    /**
     * @param target the proposed next state
     * @return true iff {@code target} is exactly the legal next state from this state.
     */
    public boolean canTransitionTo(TicketStatus target) {
        return next().map(n -> n == target).orElse(false);
    }
}

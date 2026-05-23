package com.att.tdp.issueflow.dependencies.repository;

import com.att.tdp.issueflow.common.enums.TicketStatus;

/**
 * JPQL projection for {@code GET /tickets/{ticketId}/dependencies} (spec 07 §5).
 *
 * <p>Exposes exactly the three fields the spec asks for — {@code id, title,
 * status} of each blocker. Filled by a single JPQL constructor expression
 * in {@link TicketDependencyRepository#findBlockerSummariesByTicketId(Long)}
 * to avoid the "fetch dep ids then bulk-fetch tickets" two-trip pattern
 * that would N+1 from any future list view.
 */
public record BlockerSummary(Long id, String title, TicketStatus status) {
}

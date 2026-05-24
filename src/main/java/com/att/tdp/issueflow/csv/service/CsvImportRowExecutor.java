package com.att.tdp.issueflow.csv.service;

import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import com.att.tdp.issueflow.tickets.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-row transactional wrapper around {@link TicketService#create} — the
 * mechanism that makes spec 10 §8 work ("valid rows are still created"
 * even when a sibling row fails).
 *
 * <p><b>Why a separate bean?</b> Spring's transactional advice is applied
 * via AOP proxies. If {@code CsvImportService} called a
 * {@code @Transactional(REQUIRES_NEW)} method on itself, the call would
 * bypass the proxy and inherit whatever transaction the caller was in
 * (or none at all). The two-bean split is the canonical Spring idiom:
 * the orchestrator (importer) has no transaction; each row goes through
 * this collaborator which owns the per-row {@code REQUIRES_NEW}
 * boundary.
 *
 * <p><b>What about {@code TicketService.create}'s own
 * {@code @Transactional}?</b> Default propagation is {@code REQUIRED} —
 * it joins our new transaction. Net effect: one fresh transaction per
 * row, scoped to {@link #createInIsolation}.
 *
 * <p><b>Audit row?</b> Free — {@code TicketService.create} already
 * writes the {@code AuditAction.CREATE} row inside its transaction
 * (slice 7 wiring). Spec 10 §9 ("ONE row per successfully imported
 * ticket") is satisfied automatically. Session 11 D5 — the cross-cutting
 * audit dividend in action.
 */
@Service
@RequiredArgsConstructor
public class CsvImportRowExecutor {

    private final TicketService tickets;

    /**
     * Open a new transaction, create one ticket, commit (or roll back if
     * the ticket-service call throws).
     *
     * <p>Any exception propagates to the caller so it can be bucketed
     * into {@code errors[]}. The transaction has already been rolled
     * back by the time the caller sees the exception (Spring's standard
     * {@code REQUIRES_NEW} semantics), so the failure leaves no partial
     * state visible to subsequent rows.
     *
     * @return the id of the newly-created ticket (handy for tests + the
     *         slice-13 auto-assigner audit-row assertion in
     *         {@code AutoAssignIntegrationTest}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createInIsolation(CreateTicketRequest req) {
        // Slice 13: CSV import inherits auto-assignment automatically.
        // TicketService.create() now routes a null req.assigneeId()
        // through AutoAssigner.pickAssignee(projectId) and writes the
        // spec-12 §1 AUTO_ASSIGN/SYSTEM audit row in the same REQUIRES_NEW
        // tx — per-row isolation extends to the auto-assign side-effect.
        return tickets.create(req).getId();
    }
}

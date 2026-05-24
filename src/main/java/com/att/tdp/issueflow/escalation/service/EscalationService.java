package com.att.tdp.issueflow.escalation.service;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-escalation core (spec 13 §Algorithm) — slice 14.
 *
 * <p><b>Trigger:</b> NOT invoked from here. The cron-scheduled wrapper
 * {@link EscalationScheduler} calls {@link #runEscalationPass()} on a
 * {@code fixedDelay} from {@code @Scheduled}. Tests call this method
 * directly (D3 — deterministic, no time race).
 *
 * <p><b>Per-pass behavior:</b>
 * <ol>
 *   <li>Fetch all overdue tickets via
 *       {@link TicketRepository#findOverdueForEscalation(java.time.Instant)}.
 *       {@code dueDate IS NULL} and DONE tickets and soft-deleted tickets
 *       are filtered out by the query / by the slice-9
 *       {@code @SQLRestriction}.</li>
 *   <li>For each ticket, branch on current priority:
 *       <ul>
 *         <li>{@code priority < CRITICAL} → bump one level via the
 *             pre-built {@link Priority#escalate()} (slice 1). Save.
 *             Write a {@link AuditAction#AUTO_ESCALATE}/SYSTEM row with
 *             {@code diff = {"from":"<old>","to":"<new>"}} per spec §2.
 *             Do NOT touch {@code isOverdue} — it stays {@code false}
 *             because the ticket WAS escalated. Next pass will
 *             re-evaluate.</li>
 *         <li>{@code priority == CRITICAL} AND {@code !isOverdue} →
 *             flip {@code isOverdue=true}. Write an audit row with
 *             {@code diff = {"isOverdue":{"from":false,"to":true}}}
 *             — Session 14 D8: state changed, so it earns a row.</li>
 *         <li>{@code priority == CRITICAL} AND already
 *             {@code isOverdue} → no-op, NO row. Spec §3 idempotency:
 *             prevents the same CRITICAL ticket from accumulating one
 *             AUTO_ESCALATE row every 5 minutes forever.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Manual PATCH reset:</b> when a user PATCHes
 * {@code priority} to a different value, {@code TicketService.update}
 * (slice 5 D6) sets {@code isOverdue=false} alongside the new
 * priority. The next escalation pass then re-evaluates from the new
 * priority — no special "skip" flag needed (spec §1). Slice 14 adds
 * ZERO code for this; the integration test verifies the interplay.
 *
 * <p><b>Transaction granularity:</b> class-level {@code @Transactional}
 * — one tx per {@code runEscalationPass()} call (spec §6 allows
 * either per-batch or per-ticket — D4 picks per-batch as simpler).
 * Per-ticket {@code ObjectOptimisticLockingFailureException} is
 * caught + logged so one stale-version conflict (e.g. a user
 * concurrently PATCHing the ticket during the scheduler pass) doesn't
 * roll back every other ticket's escalation. The catch is narrow on
 * purpose: any other exception bubbles and rolls back the whole batch
 * (we want loud failures for misconfiguration / DB outages).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final TicketRepository tickets;
    private final AuditLogService auditLog;
    private final Clock clock;

    public void runEscalationPass() {
        List<Ticket> overdue = tickets.findOverdueForEscalation(clock.instant());
        if (overdue.isEmpty()) {
            return;
        }
        log.info("Escalation pass: {} overdue ticket(s) to consider.", overdue.size());

        int bumped = 0;
        int flagged = 0;
        int conflicts = 0;
        for (Ticket t : overdue) {
            try {
                if (processOne(t)) {
                    if (t.getPriority() == Priority.CRITICAL && t.isOverdue()) {
                        flagged++;
                    } else {
                        bumped++;
                    }
                }
            } catch (ObjectOptimisticLockingFailureException stale) {
                // A concurrent PATCH on this ticket bumped @Version between
                // our SELECT and the implicit UPDATE on commit. The spec
                // doesn't ask for retry semantics; we log + skip and let
                // the NEXT pass re-evaluate the ticket. Bounding the catch
                // to OptimisticLockingFailure keeps unrelated failures
                // (DB outage, NPE) loud and rollback-y.
                conflicts++;
                log.warn("Skipped ticket {} during escalation pass — stale version: {}",
                        t.getId(), stale.getMessage());
            }
        }
        log.info("Escalation pass done: bumped={}, flagged={}, conflicts={}.",
                bumped, flagged, conflicts);
    }

    /**
     * @return {@code true} if a state change (priority bump OR
     *         {@code isOverdue} flip) was applied, {@code false} if the
     *         ticket was at CRITICAL and already flagged (no-op, no
     *         audit row — spec §3 idempotency).
     */
    private boolean processOne(Ticket t) {
        Priority current = t.getPriority();
        Optional<Priority> next = current.escalate();
        if (next.isPresent()) {
            // Branch (a): priority < CRITICAL → bump one level.
            t.setPriority(next.get());
            // Intentionally NOT touching isOverdue here. The ticket is
            // STILL overdue right now (we just got it from the
            // findOverdueForEscalation query), but we represent that
            // by the priority bump for non-CRITICAL tickets. The
            // boolean flag is reserved for CRITICAL tickets that have
            // no priority left to bump.
            auditLog.logSystem(
                    AuditAction.AUTO_ESCALATE,
                    EntityType.TICKET,
                    t.getId(),
                    "{\"from\":\"" + current + "\",\"to\":\"" + next.get() + "\"}");
            return true;
        }
        // Branch (b) / (c): already CRITICAL.
        if (!t.isOverdue()) {
            // Branch (b): CRITICAL AND not yet flagged → flip + audit.
            t.setOverdue(true);
            auditLog.logSystem(
                    AuditAction.AUTO_ESCALATE,
                    EntityType.TICKET,
                    t.getId(),
                    "{\"isOverdue\":{\"from\":false,\"to\":true}}");
            return true;
        }
        // Branch (c): CRITICAL AND already flagged → idempotent no-op.
        // Per spec §3, "produces no audit row when no change."
        return false;
    }
}

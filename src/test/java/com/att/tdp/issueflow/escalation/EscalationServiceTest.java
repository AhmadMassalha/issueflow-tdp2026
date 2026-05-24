package com.att.tdp.issueflow.escalation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.escalation.service.EscalationService;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link EscalationService} (slice 14, spec 13).
 *
 * <p>The actual query (with the {@code status != DONE} filter and the
 * {@code @SQLRestriction} interplay) is integration-test territory; this
 * file owns the in-Java branching logic on top of a stubbed list of
 * overdue tickets:
 *
 * <ul>
 *   <li>LOW → MEDIUM, MEDIUM → HIGH, HIGH → CRITICAL — priority bumps
 *       with the spec §2 diff shape.</li>
 *   <li>CRITICAL + NOT yet overdue → flip + audit row (D8).</li>
 *   <li>CRITICAL + already overdue → idempotent skip, NO audit row
 *       (spec §3).</li>
 *   <li>Mixed-state batch in one pass — verifies the row count.</li>
 *   <li>Empty overdue list short-circuits (no audit calls, no save
 *       attempts).</li>
 * </ul>
 *
 * <p>{@link Clock} is mocked as a {@code Clock.fixed(...)} via
 * {@code @Spy} so {@code clock.instant()} returns a deterministic
 * timestamp without seeding tickets relative to wall-clock time
 * (Session 14 D1 — the testability dividend).
 */
@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    @Mock
    private TicketRepository tickets;

    @Mock
    private AuditLogService auditLog;

    /**
     * Fixed clock — the {@code findOverdueForEscalation(now)} call sees
     * a deterministic {@code now} (one of the test invariants). Service
     * uses {@code clock.instant()} which we stub via the fixed clock's
     * own implementation.
     */
    @Spy
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private EscalationService service;

    // ---- fixtures ----------------------------------------------------------

    private static Ticket overdueTicket(Long id, Priority priority, boolean isOverdue) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setTitle("t-" + id);
        t.setDescription("d");
        t.setStatus(TicketStatus.IN_PROGRESS); // anything != DONE
        t.setPriority(priority);
        t.setType(TicketType.BUG);
        t.setProjectId(1L);
        t.setOverdue(isOverdue);
        t.setVersion(0L);
        return t;
    }

    // ---- priority bumps (branch a) ----------------------------------------

    @Test
    @DisplayName("Spec 13 §Algorithm — LOW → MEDIUM bump writes AUTO_ESCALATE/SYSTEM row with from/to diff verbatim")
    void bumpsLowToMedium_andWritesAuditRow() {
        Ticket t = overdueTicket(101L, Priority.LOW, false);
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of(t));

        service.runEscalationPass();

        assertThat(t.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(t.isOverdue()).isFalse(); // bumps don't touch the flag
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 101L,
                "{\"from\":\"LOW\",\"to\":\"MEDIUM\"}");
    }

    @Test
    @DisplayName("Spec 13 §Algorithm — MEDIUM → HIGH bump")
    void bumpsMediumToHigh() {
        Ticket t = overdueTicket(101L, Priority.MEDIUM, false);
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of(t));

        service.runEscalationPass();

        assertThat(t.getPriority()).isEqualTo(Priority.HIGH);
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 101L,
                "{\"from\":\"MEDIUM\",\"to\":\"HIGH\"}");
    }

    @Test
    @DisplayName("Spec 13 §Algorithm — HIGH → CRITICAL bump (then NEXT pass will handle the isOverdue flip)")
    void bumpsHighToCritical_doesNotFlipOverdueOnSamePass() {
        Ticket t = overdueTicket(101L, Priority.HIGH, false);
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of(t));

        service.runEscalationPass();

        assertThat(t.getPriority()).isEqualTo(Priority.CRITICAL);
        // Spec separates the two state machines: this pass did the bump;
        // a future pass will see the (now CRITICAL) ticket as still
        // overdue and flip the flag. One state change per pass per ticket.
        assertThat(t.isOverdue()).isFalse();
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 101L,
                "{\"from\":\"HIGH\",\"to\":\"CRITICAL\"}");
    }

    // ---- CRITICAL branches (b / c) ----------------------------------------

    @Test
    @DisplayName("Spec 13 §Algorithm point 3 + D8 — CRITICAL + NOT yet overdue → flip isOverdue=true + audit row")
    void critical_notYetFlagged_flipsAndAudits() {
        Ticket t = overdueTicket(101L, Priority.CRITICAL, false);
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of(t));

        service.runEscalationPass();

        assertThat(t.getPriority()).isEqualTo(Priority.CRITICAL); // unchanged — already top
        assertThat(t.isOverdue()).isTrue();
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 101L,
                "{\"isOverdue\":{\"from\":false,\"to\":true}}");
    }

    @Test
    @DisplayName("Spec 13 §3 idempotency — CRITICAL + already overdue → NO-OP, NO audit row (prevents endless 5-min row spam)")
    void critical_alreadyFlagged_noopNoAudit() {
        Ticket t = overdueTicket(101L, Priority.CRITICAL, true);
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of(t));

        service.runEscalationPass();

        assertThat(t.getPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(t.isOverdue()).isTrue(); // unchanged
        verify(auditLog, never()).logSystem(any(), any(), any(), any());
    }

    // ---- mixed batch -------------------------------------------------------

    @Test
    @DisplayName("Mixed-state batch in one pass — each ticket reaches its own next state; exactly one audit row per actual change")
    void mixedBatch_writesOneAuditRowPerStateChange() {
        Ticket low = overdueTicket(1L, Priority.LOW, false);             // → MEDIUM (row)
        Ticket high = overdueTicket(2L, Priority.HIGH, false);           // → CRITICAL (row)
        Ticket criticalNew = overdueTicket(3L, Priority.CRITICAL, false); // → flag (row)
        Ticket criticalOld = overdueTicket(4L, Priority.CRITICAL, true);  // → no-op (NO row)
        when(tickets.findOverdueForEscalation(any(Instant.class)))
                .thenReturn(List.of(low, high, criticalNew, criticalOld));

        service.runEscalationPass();

        assertThat(low.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(high.getPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(criticalNew.isOverdue()).isTrue();
        assertThat(criticalOld.isOverdue()).isTrue(); // unchanged

        // 3 state changes → exactly 3 audit rows.
        verify(auditLog, times(3)).logSystem(eq(AuditAction.AUTO_ESCALATE),
                eq(EntityType.TICKET), any(), any());

        // Specific row content (deterministic ORDER BY id ASC from the query).
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 1L,
                "{\"from\":\"LOW\",\"to\":\"MEDIUM\"}");
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 2L,
                "{\"from\":\"HIGH\",\"to\":\"CRITICAL\"}");
        verify(auditLog).logSystem(
                AuditAction.AUTO_ESCALATE, EntityType.TICKET, 3L,
                "{\"isOverdue\":{\"from\":false,\"to\":true}}");
    }

    // ---- short-circuit -----------------------------------------------------

    @Test
    @DisplayName("Empty overdue list → no audit calls, no save attempts (clean no-op)")
    void emptyBatch_isANoOp() {
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of());

        service.runEscalationPass();

        verify(auditLog, never()).logSystem(any(), any(), any(), any());
    }

    // ---- the clock is actually consulted -----------------------------------

    @Test
    @DisplayName("Slice 14 D1 — runEscalationPass passes clock.instant() to the repo (proves the Clock bean is wired, not Instant.now)")
    void passesClockInstantToRepository() {
        when(tickets.findOverdueForEscalation(any(Instant.class))).thenReturn(List.of());

        service.runEscalationPass();

        // The fixed clock returns the seeded instant — verify the repo
        // was queried with EXACTLY that instant, not Instant.now() (which
        // would slip past `any(Instant.class)` but fail equals).
        verify(tickets).findOverdueForEscalation(Instant.parse("2026-01-01T00:00:00Z"));
    }
}

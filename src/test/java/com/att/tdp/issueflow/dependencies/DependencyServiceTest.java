package com.att.tdp.issueflow.dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.dependencies.domain.TicketDependency;
import com.att.tdp.issueflow.dependencies.repository.TicketDependencyRepository;
import com.att.tdp.issueflow.dependencies.service.DependencyService;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branching coverage for {@link DependencyService}. Spec 07 §1–§6 + the
 * five validation arms in the documented order (Session 08 D7) + cycle
 * detection (Session 08 D2/D10) + the cross-feature
 * {@link DependencyService#hasOpenBlockers(Long)} (spec 04 §9).
 *
 * <p>Each validation-order test triggers an input that hits multiple
 * checks; the assertion is that the FIRST one in the documented order
 * is the one that throws. That nails the order down as part of the
 * contract — a future refactor that accidentally swaps two checks would
 * trip these tests.
 *
 * <p>Cycle detection is tested at three depths (1-hop, 2-hop, 3-hop) plus
 * a fan-in negative case (multiple paths to the same node ≠ a cycle).
 * The depth-100 safety cap is tested with a synthetic 101-deep chain
 * that the BFS must terminate cleanly on.
 */
@ExtendWith(MockitoExtension.class)
class DependencyServiceTest {

    @Mock
    private TicketDependencyRepository deps;

    @Mock
    private TicketRepository tickets;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private DependencyService service;

    // ---- fixtures -----------------------------------------------------------

    private static Ticket ticketInProject(Long id, Long projectId) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setProjectId(projectId);
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setTitle("t" + id);
        t.setOverdue(false);
        return t;
    }

    /** Stub for the happy path: both tickets exist and live in project 100. */
    private void stubBothTicketsExist(Long ticketId, Long blockerId) {
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticketInProject(ticketId, 100L)));
        when(tickets.findById(blockerId)).thenReturn(Optional.of(ticketInProject(blockerId, 100L)));
    }

    // ---- validation arm 1: self-dependency ---------------------------------

    @Test
    @DisplayName("Spec 07 §2 — self-dependency (ticketId == blockerId): 422 DEPENDENCY_SELF (fires before any DB lookup)")
    void add_throwsDependencySelf_whenSamePair() {
        assertThatThrownBy(() -> service.add(7L, 7L))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_SELF);
        verify(tickets, never()).findById(anyLong()); // arm 1 fires before arm 2
    }

    // ---- validation arm 2: tickets exist -----------------------------------

    @Test
    @DisplayName("Spec 07 — missing ticket: 404 TICKET_NOT_FOUND")
    void add_throwsTicketNotFound_whenTicketMissing() {
        when(tickets.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(1L, 2L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    @DisplayName("Spec 07 — missing blocker: 404 TICKET_NOT_FOUND")
    void add_throwsTicketNotFound_whenBlockerMissing() {
        when(tickets.findById(1L)).thenReturn(Optional.of(ticketInProject(1L, 100L)));
        when(tickets.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(1L, 2L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    // ---- validation arm 3: same project ------------------------------------

    @Test
    @DisplayName("Spec 07 §1 — cross-project: 422 DEPENDENCY_DIFFERENT_PROJECT (fires before duplicate / cycle check)")
    void add_throwsDifferentProject_whenProjectsDiffer() {
        when(tickets.findById(1L)).thenReturn(Optional.of(ticketInProject(1L, 100L)));
        when(tickets.findById(2L)).thenReturn(Optional.of(ticketInProject(2L, 200L)));

        assertThatThrownBy(() -> service.add(1L, 2L))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_DIFFERENT_PROJECT);
        verify(deps, never()).existsByTicketIdAndBlockerId(anyLong(), anyLong());
    }

    // ---- validation arm 4: duplicate ---------------------------------------

    @Test
    @DisplayName("Spec 07 §3 — duplicate pair: 409 DEPENDENCY_EXISTS (fires before cycle BFS)")
    void add_throwsDependencyExists_whenPairExists() {
        stubBothTicketsExist(1L, 2L);
        when(deps.existsByTicketIdAndBlockerId(1L, 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.add(1L, 2L))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_EXISTS);
        verify(deps, never()).findBlockerIdsByTicketIds(anyCollection());
    }

    // ---- validation arm 5: cycle (1-hop) ------------------------------------

    @Test
    @DisplayName("Spec 07 §4 — 1-hop cycle: A is blocked by B; new edge B->A would cycle. 422 DEPENDENCY_CYCLE")
    void add_throwsDependencyCycle_onOneHop() {
        stubBothTicketsExist(2L, 1L); // request: add 2 blocked-by 1
        when(deps.existsByTicketIdAndBlockerId(2L, 1L)).thenReturn(false);
        // BFS from blockerId=1: follow its blockers. 1 -> [2]. ticketId=2 found.
        when(deps.findBlockerIdsByTicketIds(List.of(1L))).thenReturn(List.of(2L));

        assertThatThrownBy(() -> service.add(2L, 1L))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_CYCLE);
        verify(deps, never()).save(any());
    }

    // ---- validation arm 5: cycle (2-hop) ------------------------------------

    @Test
    @DisplayName("Spec 07 §4 — 2-hop cycle: 1->2->3; new edge 3->1 would cycle. 422 DEPENDENCY_CYCLE")
    void add_throwsDependencyCycle_onTwoHop() {
        stubBothTicketsExist(3L, 1L); // request: add 3 blocked-by 1
        when(deps.existsByTicketIdAndBlockerId(3L, 1L)).thenReturn(false);
        // BFS from blockerId=1: level 1 -> [2]; level 2 -> [3]. ticketId=3 found.
        when(deps.findBlockerIdsByTicketIds(List.of(1L))).thenReturn(List.of(2L));
        when(deps.findBlockerIdsByTicketIds(List.of(2L))).thenReturn(List.of(3L));

        assertThatThrownBy(() -> service.add(3L, 1L))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_CYCLE);
    }

    // ---- validation arm 5: cycle (3-hop) ------------------------------------

    @Test
    @DisplayName("Spec 07 §4 — 3-hop cycle: 1->2->3->4; new edge 4->1 would cycle. 422 DEPENDENCY_CYCLE")
    void add_throwsDependencyCycle_onThreeHop() {
        stubBothTicketsExist(4L, 1L);
        when(deps.existsByTicketIdAndBlockerId(4L, 1L)).thenReturn(false);
        when(deps.findBlockerIdsByTicketIds(List.of(1L))).thenReturn(List.of(2L));
        when(deps.findBlockerIdsByTicketIds(List.of(2L))).thenReturn(List.of(3L));
        when(deps.findBlockerIdsByTicketIds(List.of(3L))).thenReturn(List.of(4L));

        assertThatThrownBy(() -> service.add(4L, 1L))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_CYCLE);
    }

    // ---- fan-in (multiple paths to same node) is NOT a cycle ---------------

    @Test
    @DisplayName("Spec 07 §4 — fan-in: 5 has two distinct paths to node 9, but 9 isn't ticketId. NOT a cycle.")
    void add_succeeds_whenFanInButNoCycle() {
        stubBothTicketsExist(5L, 6L);
        when(deps.existsByTicketIdAndBlockerId(5L, 6L)).thenReturn(false);
        // BFS from blockerId=6: level 1 -> [7, 8]; level 2 -> [9, 9] (dedup via
        // visited set in DependencyService); level 3 -> [] (9 has no blockers).
        // ticketId=5 never appears, so no cycle.
        when(deps.findBlockerIdsByTicketIds(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Collection<Long> frontier = inv.getArgument(0);
            if (frontier.contains(6L)) return List.of(7L, 8L);
            if (frontier.contains(7L) || frontier.contains(8L)) return List.of(9L, 9L);
            return List.of(); // 9 has no further blockers — chain ends
        });
        when(deps.save(any(TicketDependency.class))).thenAnswer(inv -> {
            TicketDependency d = inv.getArgument(0);
            d.setId(99L);
            return d;
        });

        TicketDependency saved = service.add(5L, 6L);

        assertThat(saved.getTicketId()).isEqualTo(5L);
        assertThat(saved.getBlockerId()).isEqualTo(6L);
        verify(auditLog).log(AuditAction.CREATE, EntityType.DEPENDENCY, 99L);
    }

    // ---- BFS safety cap -----------------------------------------------------

    @Test
    @DisplayName("Cycle BFS depth-cap (100): a 101-deep linear chain is allowed (no cycle found within safety horizon)")
    void add_handlesDeepChain_withoutStackOverflow() {
        // Linear chain: blockerId=1 -> 2 -> 3 -> ... -> N. ticketId=999 isn't
        // in the chain, so no cycle. We only need the BFS to terminate
        // cleanly. Returning a fresh "next" id per level for the first 100
        // calls then empty is enough.
        stubBothTicketsExist(999L, 1L);
        when(deps.existsByTicketIdAndBlockerId(999L, 1L)).thenReturn(false);
        // Use any-matcher answer to simulate "each level reveals one new id"
        // up to depth 100, then exhausts.
        when(deps.findBlockerIdsByTicketIds(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Collection<Long> frontier = inv.getArgument(0);
            // The deepest id we've seen + 1; depth-cap stops us before id > 101.
            long next = frontier.stream().mapToLong(Long::longValue).max().orElse(0L) + 1;
            return List.of(next);
        });
        when(deps.save(any(TicketDependency.class))).thenAnswer(inv -> {
            TicketDependency d = inv.getArgument(0);
            d.setId(1234L);
            return d;
        });

        // Must not throw / stack-overflow / hang.
        service.add(999L, 1L);

        // The BFS made exactly 100 repository calls (the documented depth-cap
        // from spec 07 §4; D2). If anyone bumps the cap, this test will be the
        // first to catch it — and they should add a row to prompts.md.
        verify(deps, times(100)).findBlockerIdsByTicketIds(any());
    }

    // ---- remove -------------------------------------------------------------

    @Test
    @DisplayName("Spec 07 §6 — remove: deletes the row and writes audit DELETE")
    void remove_deletesAndAudits() {
        TicketDependency dep = new TicketDependency();
        dep.setId(42L);
        dep.setTicketId(1L);
        dep.setBlockerId(2L);
        when(deps.findByTicketIdAndBlockerId(1L, 2L)).thenReturn(Optional.of(dep));

        service.remove(1L, 2L);

        verify(deps).delete(dep);
        verify(auditLog).log(AuditAction.DELETE, EntityType.DEPENDENCY, 42L);
    }

    @Test
    @DisplayName("Spec 07 §6 — remove: 404 DEPENDENCY_NOT_FOUND when row doesn't exist (tenancy idiom)")
    void remove_throws404_whenMissing() {
        when(deps.findByTicketIdAndBlockerId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(1L, 2L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DEPENDENCY_NOT_FOUND);
        verify(deps, never()).delete(any(TicketDependency.class));
        verify(auditLog, never()).log(any(), any(), anyLong());
    }

    // ---- listBlockers -------------------------------------------------------

    @Test
    @DisplayName("listBlockers: 404 TICKET_NOT_FOUND when the ticket itself doesn't exist")
    void listBlockers_throws404_whenTicketMissing() {
        when(tickets.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.listBlockers(99L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    // ---- hasOpenBlockers ----------------------------------------------------

    @Test
    @DisplayName("hasOpenBlockers: true when countOpenBlockers > 0")
    void hasOpenBlockers_isTrue_whenAnyOpen() {
        when(deps.countOpenBlockers(1L)).thenReturn(2L);

        assertThat(service.hasOpenBlockers(1L)).isTrue();
    }

    @Test
    @DisplayName("hasOpenBlockers: false when countOpenBlockers == 0 (no blockers OR all DONE)")
    void hasOpenBlockers_isFalse_whenZero() {
        when(deps.countOpenBlockers(1L)).thenReturn(0L);

        assertThat(service.hasOpenBlockers(1L)).isFalse();
    }
}

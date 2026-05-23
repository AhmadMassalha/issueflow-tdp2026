package com.att.tdp.issueflow.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.dependencies.service.DependencyService;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import com.att.tdp.issueflow.tickets.api.PatchTicketRequest;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import com.att.tdp.issueflow.tickets.service.TicketService;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branching-logic tests for {@link TicketService}.
 *
 * <p>Spec 04 §1–§11 are covered here (§12 is repository-level, covered by
 * {@code TicketRepositoryJpaTest}). One test per branch — including the FSM
 * skip/backward arms, the version-required + version-stale arms, the DONE-
 * immutable arm, the partial assignee validation, and the §10 priority-
 * change-clears-overdue rule.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository tickets;

    @Mock
    private ProjectRepository projects;

    @Mock
    private UserRepository users;

    /** Slice 7 wiring — see AuditIntegrationTest for cross-cutting proof. */
    @Mock
    private AuditLogService auditLog;

    /**
     * Slice 8 wiring. The DONE-blocker arm has two dedicated tests below
     * ({@link #should_allowTransitionToDone_whenNoOpenBlockers},
     * {@link #should_rejectTransitionToDone_whenOpenBlockersExist}); every
     * other test stubs {@code hasOpenBlockers(...)} via Mockito's default
     * (returns false), which mirrors "ticket has no dependencies at all".
     */
    @Mock
    private DependencyService dependencies;

    @InjectMocks
    private TicketService service;

    // ---- fixtures -----------------------------------------------------------

    private static CreateTicketRequest createReq(Long projectId, Long assigneeId) {
        return new CreateTicketRequest(
                "title", "desc", null, Priority.MEDIUM, TicketType.FEATURE,
                projectId, assigneeId, null);
    }

    private static Ticket existing(Long id, TicketStatus status, Priority priority, Long version) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setTitle("seed");
        t.setDescription("seed desc");
        t.setStatus(status);
        t.setPriority(priority);
        t.setType(TicketType.FEATURE);
        t.setProjectId(1L);
        t.setVersion(version);
        t.setOverdue(false);
        return t;
    }

    private static User dev(Long id) {
        User u = new User();
        u.setId(id);
        u.setRole(Role.DEVELOPER);
        return u;
    }

    private static User admin(Long id) {
        User u = new User();
        u.setId(id);
        u.setRole(Role.ADMIN);
        return u;
    }

    // ---- create -------------------------------------------------------------

    @Test
    @DisplayName("Spec 04 §5 — create: defaults status=TODO, isOverdue=false, version starts at 0 (set by JPA)")
    void should_create_withDefaults() {
        when(projects.existsById(1L)).thenReturn(true);
        when(tickets.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        Ticket saved = service.create(createReq(1L, null));

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(captor.capture());
        Ticket persisted = captor.getValue();
        assertThat(persisted.getStatus()).isEqualTo(TicketStatus.TODO);
        assertThat(persisted.isOverdue()).isFalse();
        assertThat(persisted.getProjectId()).isEqualTo(1L);
        assertThat(persisted.getAssigneeId()).isNull(); // §4: omitted assignee → slice-13 auto-assigner (no-op here)
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    @DisplayName("Spec 04 §3 — create: project missing → 404 PROJECT_NOT_FOUND; ticket repo never touched")
    void should_throw404_whenProjectMissing() {
        when(projects.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(createReq(99L, null)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);

        verify(tickets, never()).save(any());
        verify(users, never()).findById(any());
    }

    @Test
    @DisplayName("Spec 04 §4 — create: supplied assignee that is not a DEVELOPER → 422 INVALID_ASSIGNEE")
    void should_throw422_whenAssigneeIsNotDeveloper() {
        when(projects.existsById(1L)).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(admin(7L)));

        assertThatThrownBy(() -> service.create(createReq(1L, 7L)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_ASSIGNEE);

        verify(tickets, never()).save(any());
    }

    @Test
    @DisplayName("Spec 04 §4 — create: supplied assignee that doesn't exist → 422 INVALID_ASSIGNEE (same as wrong role)")
    void should_throw422_whenAssigneeMissing() {
        when(projects.existsById(1L)).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(createReq(1L, 7L)))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_ASSIGNEE);
    }

    @Test
    @DisplayName("create: valid DEVELOPER assignee is accepted and persisted")
    void should_create_withValidDeveloperAssignee() {
        when(projects.existsById(1L)).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(dev(7L)));
        when(tickets.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(createReq(1L, 7L));

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(captor.capture());
        assertThat(captor.getValue().getAssigneeId()).isEqualTo(7L);
    }

    // ---- findById -----------------------------------------------------------

    @Test
    @DisplayName("findById — throws TICKET_NOT_FOUND when absent (spec §6/§7 base for PATCH/DELETE)")
    void should_throw404_whenIdMissing() {
        when(tickets.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    // ---- update: version-required / version-stale --------------------------

    @Test
    @DisplayName("Spec 04 §6 — PATCH without 'version' → 400 VERSION_REQUIRED; never touches the repo")
    void should_throw400_whenVersionMissing() {
        PatchTicketRequest req = new PatchTicketRequest(
                "new title", null, null, null, null, null, null, /*version=*/ null);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VERSION_REQUIRED);

        verify(tickets, never()).findById(any());
    }

    @Test
    @DisplayName("Spec 04 §6 — PATCH with stale 'version' → 409 TICKET_VERSION_CONFLICT (service-level fast path)")
    void should_throw409_whenVersionStale() {
        Ticket cur = existing(1L, TicketStatus.TODO, Priority.MEDIUM, /*version=*/ 5L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                "new title", null, null, null, null, null, null, /*version=*/ 4L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_VERSION_CONFLICT);
    }

    // ---- update: DONE-is-immutable -----------------------------------------

    @Test
    @DisplayName("Spec 04 §8 — PATCH on a DONE ticket → 409 TICKET_DONE_IS_IMMUTABLE, even for trivial field changes")
    void should_throw409_whenTicketAlreadyDone() {
        Ticket cur = existing(1L, TicketStatus.DONE, Priority.MEDIUM, 3L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                "tweak", null, null, null, null, null, null, /*version=*/ 3L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_DONE_IS_IMMUTABLE);
    }

    // ---- update: FSM (§7) --------------------------------------------------

    @Test
    @DisplayName("Spec 04 §7 — happy FSM step: TODO → IN_PROGRESS (one step forward) is allowed")
    void should_allowOneStepForward() {
        Ticket cur = existing(1L, TicketStatus.TODO, Priority.MEDIUM, 0L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, 0L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Spec 04 §7 / Session-05 D4 — no-op (status = current) is intentionally allowed")
    void should_allowNoOpStatus() {
        Ticket cur = existing(1L, TicketStatus.IN_PROGRESS, Priority.MEDIUM, 1L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                "new title", null, TicketStatus.IN_PROGRESS, null, null, null, null, 1L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(updated.getTitle()).isEqualTo("new title");
    }

    @Test
    @DisplayName("Spec 04 §7 — skipping a step (TODO → IN_REVIEW) → 409 TICKET_INVALID_TRANSITION")
    void should_throw409_whenSkippingStep() {
        Ticket cur = existing(1L, TicketStatus.TODO, Priority.MEDIUM, 0L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.IN_REVIEW, null, null, null, null, 0L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("Spec 04 §7 — going backward (IN_REVIEW → TODO) → 409 TICKET_INVALID_TRANSITION")
    void should_throw409_whenGoingBackward() {
        Ticket cur = existing(1L, TicketStatus.IN_REVIEW, Priority.MEDIUM, 2L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.TODO, null, null, null, null, 2L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_INVALID_TRANSITION);
    }

    // ---- update: §9 DONE-blocker (slice 8 cross-feature) -------------------

    @Test
    @DisplayName("Spec 04 §9 (slice 8) — IN_REVIEW → DONE allowed when DependencyService reports no open blockers")
    void should_allowTransitionToDone_whenNoOpenBlockers() {
        Ticket cur = existing(1L, TicketStatus.IN_REVIEW, Priority.MEDIUM, 2L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));
        when(dependencies.hasOpenBlockers(1L)).thenReturn(false);

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.DONE, null, null, null, null, 2L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.getStatus()).isEqualTo(TicketStatus.DONE);
    }

    @Test
    @DisplayName("Spec 04 §9 (slice 8) — IN_REVIEW → DONE rejected with TICKET_HAS_OPEN_BLOCKERS when blockers remain")
    void should_rejectTransitionToDone_whenOpenBlockersExist() {
        Ticket cur = existing(1L, TicketStatus.IN_REVIEW, Priority.MEDIUM, 2L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));
        when(dependencies.hasOpenBlockers(1L)).thenReturn(true);

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.DONE, null, null, null, null, 2L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_HAS_OPEN_BLOCKERS);
        // Status was NOT applied — the guard runs BEFORE setStatus.
        assertThat(cur.getStatus()).isEqualTo(TicketStatus.IN_REVIEW);
    }

    // ---- update: §10 priority change clears isOverdue ----------------------

    @Test
    @DisplayName("Spec 04 §10 — manual priority change clears isOverdue")
    void should_clearIsOverdue_whenPriorityChanged() {
        Ticket cur = existing(1L, TicketStatus.IN_PROGRESS, Priority.MEDIUM, 1L);
        cur.setOverdue(true);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, null, Priority.HIGH, null, null, null, 1L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(updated.isOverdue()).isFalse();
    }

    @Test
    @DisplayName("Spec 04 §10 — sending the same priority is NOT a 'change' and does not clear isOverdue")
    void should_notClearIsOverdue_whenPriorityUnchanged() {
        Ticket cur = existing(1L, TicketStatus.IN_PROGRESS, Priority.MEDIUM, 1L);
        cur.setOverdue(true);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, null, Priority.MEDIUM, null, null, null, 1L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.isOverdue()).isTrue(); // unchanged: escalation flag survives
    }

    // ---- update: reassignment (D2) -----------------------------------------

    @Test
    @DisplayName("Session-05 D2 — PATCH can reassign; same DEVELOPER-role check applies")
    void should_reassignOnPatch_whenAssigneeIsDeveloper() {
        Ticket cur = existing(1L, TicketStatus.IN_PROGRESS, Priority.MEDIUM, 1L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));
        when(users.findById(9L)).thenReturn(Optional.of(dev(9L)));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, null, null, null, /*assigneeId=*/ 9L, null, 1L);

        Ticket updated = service.update(1L, req);

        assertThat(updated.getAssigneeId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("PATCH reassign to a non-DEVELOPER → 422 INVALID_ASSIGNEE; status not mutated")
    void should_throw422_onPatchReassignToNonDeveloper() {
        Ticket cur = existing(1L, TicketStatus.TODO, Priority.MEDIUM, 0L);
        when(tickets.findById(1L)).thenReturn(Optional.of(cur));
        when(users.findById(9L)).thenReturn(Optional.of(admin(9L)));

        PatchTicketRequest req = new PatchTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, 9L, null, 0L);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_ASSIGNEE);

        assertThat(cur.getStatus()).isEqualTo(TicketStatus.TODO); // not mutated
    }

    // ---- update: not found --------------------------------------------------

    @Test
    @DisplayName("update — throws TICKET_NOT_FOUND when target missing (after version validation)")
    void should_throw404_whenUpdateTargetMissing() {
        when(tickets.findById(7L)).thenReturn(Optional.empty());

        PatchTicketRequest req = new PatchTicketRequest(
                "x", null, null, null, null, null, null, 0L);

        assertThatThrownBy(() -> service.update(7L, req))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    // ---- delete -------------------------------------------------------------

    @Test
    @DisplayName("delete — calls repo.deleteById when target exists (hard delete; slice 9 swaps to soft)")
    void should_deleteWhenPresent() {
        when(tickets.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(tickets, times(1)).deleteById(7L);
    }

    @Test
    @DisplayName("delete — TICKET_NOT_FOUND without touching deleteById when absent")
    void should_throw404_whenDeleteTargetMissing() {
        when(tickets.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);

        verify(tickets, never()).deleteById(any());
    }
}

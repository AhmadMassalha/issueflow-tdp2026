package com.att.tdp.issueflow.tickets.service;

import com.att.tdp.issueflow.assign.service.AutoAssigner;
import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.dependencies.service.DependencyService;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import com.att.tdp.issueflow.tickets.api.PatchTicketRequest;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the {@code tickets} feature.
 *
 * <p>Implements spec 04 §1–§12. Cross-slice wiring history:
 * <ul>
 *   <li>§4 auto-assignment when {@code assigneeId} is omitted — wired in
 *       slice 13 via {@link AutoAssigner#pickAssignee(Long)}, called
 *       inside {@link #create} in the same transaction. Writes a
 *       {@code AUTO_ASSIGN/SYSTEM} audit row when a candidate is found
 *       (spec 12 §1).</li>
 *   <li>§4 "DEVELOPER active in that project" check — slice 13 D7 closes
 *       the Session-05 D1 deferral. {@link #assertValidAssignee} now
 *       checks both halves: role = DEVELOPER AND member of the project
 *       (ADR 0007). Same enforcement on POST and PATCH.</li>
 *   <li>§9 "transition to DONE blocked by open blockers" — wired in slice 8
 *       via {@link DependencyService#hasOpenBlockers(Long)}, called inside
 *       the FSM block below before applying the new status.</li>
 *   <li>§11 soft delete — slice 9 swaps storage via {@code @SQLDelete}; for
 *       slice 5 {@link #delete} is a hard delete consistent with the existing
 *       {@code UserService}/{@code ProjectService} pattern.</li>
 * </ul>
 *
 * <p>Optimistic locking (§6) is enforced twice (ADR 0001): a fast service-
 * level pre-check, plus the JPA flush-time mapping in
 * {@code GlobalExceptionHandler.handleOptimisticLock}. See {@link #update}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final AuditLogService auditLog;
    private final DependencyService dependencies;
    private final AutoAssigner autoAssigner;

    @Transactional(readOnly = true)
    public List<Ticket> findByProjectId(Long projectId) {
        return tickets.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Ticket findById(Long id) {
        return tickets.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.TICKET_NOT_FOUND, "Ticket " + id + " was not found."));
    }

    // ---- create -------------------------------------------------------------

    public Ticket create(CreateTicketRequest req) {
        // §3: project must exist (and not be soft-deleted — slice-9
        // @SQLRestriction handles that automatically).
        if (!projects.existsById(req.projectId())) {
            throw new NotFoundException(
                    ErrorCode.PROJECT_NOT_FOUND,
                    "Project " + req.projectId() + " was not found.");
        }

        // §4: assignee resolution. Slice 13:
        //   * If supplied, validate it's a DEVELOPER AND a member of THIS
        //     project (D7 closes Session-05 D1's deferred half).
        //   * If omitted, ask the auto-assigner. Empty Optional → leave null
        //     per spec 12 §Algorithm point 4 ("if no candidates exist for
        //     that project, set assigneeId = null. Do NOT raise an error.").
        Long resolvedAssigneeId = req.assigneeId();
        boolean autoAssigned = false;
        if (resolvedAssigneeId != null) {
            assertValidAssignee(resolvedAssigneeId, req.projectId());
        } else {
            Optional<Long> picked = autoAssigner.pickAssignee(req.projectId());
            if (picked.isPresent()) {
                resolvedAssigneeId = picked.get();
                autoAssigned = true;
            }
        }

        Ticket t = new Ticket();
        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setStatus(req.status() != null ? req.status() : TicketStatus.TODO); // §5 default
        t.setPriority(req.priority());
        t.setType(req.type());
        t.setProjectId(req.projectId());
        t.setAssigneeId(resolvedAssigneeId);
        t.setDueDate(req.dueDate());
        t.setOverdue(false);    // §5: isOverdue:false on create
        Ticket saved = tickets.save(t); // @Version starts at 0 on insert

        // Spec 06 §1: USER actor for the create itself.
        auditLog.log(AuditAction.CREATE, EntityType.TICKET, saved.getId());

        // Spec 12 §1: SECOND row for the auto-assignment, actor=SYSTEM,
        // diff={"assigneeId":<id>}. Same transaction as the CREATE row
        // and the entity save (spec 12 §4) — failure rolls all three back.
        // Session 13 D2: two rows, not a reshaped CREATE — actors differ
        // (the POSTer did the create; the system did the assignment).
        if (autoAssigned) {
            auditLog.logSystem(
                    AuditAction.AUTO_ASSIGN,
                    EntityType.TICKET,
                    saved.getId(),
                    "{\"assigneeId\":" + resolvedAssigneeId + "}");
        }
        return saved;
    }

    // ---- update -------------------------------------------------------------

    public Ticket update(Long id, PatchTicketRequest req) {
        // §6 (a): VERSION_REQUIRED before anything else. Done here (not as
        // @NotNull on the DTO) so we can emit the feature-specific code.
        if (req.version() == null) {
            throw new ValidationException(
                    ErrorCode.VERSION_REQUIRED,
                    "version is required for PATCH /tickets/{id}.",
                    List.of(new ApiError.FieldIssue(
                            "version",
                            "must be the version returned by the most recent GET/POST/PATCH")));
        }

        Ticket existing = findById(id);

        // §6 (b): service-level pre-check. The JPA flush-time fallback in
        // GlobalExceptionHandler.handleOptimisticLock covers the race window
        // between this check and commit (ADR 0001).
        if (!existing.getVersion().equals(req.version())) {
            throw new ConflictException(
                    ErrorCode.TICKET_VERSION_CONFLICT,
                    "Ticket " + id + " was modified by another transaction (yours: v"
                            + req.version() + ", server: v" + existing.getVersion() + ").");
        }

        // §8: DONE is terminal — any PATCH on a DONE ticket is rejected.
        if (existing.getStatus() == TicketStatus.DONE) {
            throw new ConflictException(
                    ErrorCode.TICKET_DONE_IS_IMMUTABLE,
                    "Ticket " + id + " is DONE and cannot be modified.");
        }

        // Reassignment (Session-05 D2): same rules as on create. Slice 13 D7
        // — membership is checked against the EXISTING ticket's project
        // (the PATCH body does NOT carry projectId; spec 04 §2 makes
        // project immutable after create).
        if (req.assigneeId() != null) {
            assertValidAssignee(req.assigneeId(), existing.getProjectId());
            existing.setAssigneeId(req.assigneeId());
        }

        if (req.title() != null) {
            existing.setTitle(req.title());
        }
        if (req.description() != null) {
            existing.setDescription(req.description());
        }
        if (req.type() != null) {
            existing.setType(req.type());
        }
        if (req.dueDate() != null) {
            existing.setDueDate(req.dueDate());
        }

        // §10: manual priority change clears isOverdue. Next escalation pass
        // (slice 14) will re-evaluate from the new priority.
        if (req.priority() != null && req.priority() != existing.getPriority()) {
            existing.setPriority(req.priority());
            existing.setOverdue(false);
        }

        // §7: status FSM. No-op (status = current) is allowed per Session-05 D4.
        TicketStatus statusBefore = existing.getStatus();
        TicketStatus statusAfter = statusBefore;
        if (req.status() != null && req.status() != existing.getStatus()) {
            if (!existing.getStatus().canTransitionTo(req.status())) {
                throw new ConflictException(
                        ErrorCode.TICKET_INVALID_TRANSITION,
                        "Cannot transition ticket " + id + " from " + existing.getStatus()
                                + " to " + req.status() + ". Allowed next: "
                                + existing.getStatus().next().map(Enum::name).orElse("(none — DONE is terminal)"));
            }
            // Spec 04 §9 (Session 08 D3): transitioning to DONE requires every
            // blocker to be DONE itself. Checked AFTER the FSM allowed-edges
            // gate (so we still return TICKET_INVALID_TRANSITION on a forbidden
            // path) but BEFORE applying the status (so the audit row + version
            // bump only happen when the change actually goes through).
            if (req.status() == TicketStatus.DONE && dependencies.hasOpenBlockers(id)) {
                throw new ConflictException(
                        ErrorCode.TICKET_HAS_OPEN_BLOCKERS,
                        "Cannot mark ticket " + id + " DONE: it has open blockers. "
                                + "Resolve all blockers first or query GET /tickets/" + id
                                + "/dependencies for the list.");
            }
            existing.setStatus(req.status());
            statusAfter = req.status();
        }

        // D3: status transition is the one update where a tiny diff string
        // earns its keep (FSM-level information density). Other field changes
        // → diff stays null; reviewers can read the controller GET to see them.
        String diff = (statusBefore != statusAfter)
                ? "{\"status\":{\"from\":\"" + statusBefore + "\",\"to\":\"" + statusAfter + "\"}}"
                : null;
        auditLog.log(AuditAction.UPDATE, EntityType.TICKET, id, diff);

        return existing; // JPA dirty-checking persists on commit; @Version increments.
    }

    // ---- delete -------------------------------------------------------------

    public void delete(Long id) {
        if (!tickets.existsById(id)) {
            throw new NotFoundException(
                    ErrorCode.TICKET_NOT_FOUND, "Ticket " + id + " was not found.");
        }
        // Slice 9: @SQLDelete on Ticket makes deleteById issue an UPDATE that
        // sets deleted_at = NOW() instead of a hard DELETE. The audit semantic
        // (AuditAction.DELETE) is unchanged from the client's perspective.
        tickets.deleteById(id);
        auditLog.log(AuditAction.DELETE, EntityType.TICKET, id);
    }

    // ---- slice 9: soft-delete listing + restore -----------------------------

    /**
     * ADMIN-only listing of soft-deleted tickets for a project (spec 08
     * endpoint {@code GET /tickets/deleted?projectId=...}). Bypasses
     * {@code @SQLRestriction} via a native query (Session 09 D2).
     * Authorization is enforced at the controller layer.
     *
     * <p>Per ADR 0002 — a soft-deleted project's tickets are still active
     * (no cascade), so this endpoint only surfaces tickets that were
     * individually deleted. The deleted-project case is served by
     * {@code GET /projects/deleted}.
     */
    @Transactional(readOnly = true)
    public List<Ticket> findDeletedByProjectId(Long projectId) {
        return tickets.findDeletedByProjectId(projectId);
    }

    /**
     * ADMIN-only restore of a soft-deleted ticket (spec 08 §6 + Session 09
     * D3/D4). Three terminal states identical to {@code ProjectService.restore}:
     * 404 (truly missing), 409 (already active), 200 (restored). The native
     * UPDATE bumps {@code version} explicitly (D14) so stale-handle clients
     * don't silently overwrite the post-restore state.
     */
    public Ticket restore(Long id) {
        if (!tickets.existsByIdIncludingDeleted(id)) {
            throw new NotFoundException(
                    ErrorCode.TICKET_NOT_FOUND, "Ticket " + id + " was not found.");
        }
        if (tickets.existsById(id)) {
            throw new ConflictException(
                    ErrorCode.ALREADY_ACTIVE,
                    "Ticket " + id + " is already active; nothing to restore.");
        }
        int affected = tickets.restoreById(id);
        if (affected == 0) {
            throw new ConflictException(
                    ErrorCode.ALREADY_ACTIVE,
                    "Ticket " + id + " was concurrently restored; refresh and try again.");
        }
        auditLog.log(AuditAction.RESTORE, EntityType.TICKET, id);
        return tickets.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.TICKET_NOT_FOUND,
                        "Ticket " + id + " vanished after restore — investigate."));
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * §4 — assignee validation. Slice 13 D7 closes the Session-05 D1
     * deferred half: assignee must be (a) an existing DEVELOPER user AND
     * (b) a member of the target project per ADR 0007 (owner ∪
     * has-a-ticket-here). The membership check delegates to
     * {@link AutoAssigner#candidateIdsFor(Long)} so there is a single
     * source of truth for "is X eligible to be assigned in project P".
     *
     * <p>The two checks fire in order: existence-and-role first (cheaper,
     * doesn't touch the workload query), then membership. Both failures
     * surface as the same {@code INVALID_ASSIGNEE} 422 with a field-issue
     * pointing at {@code assigneeId} — the spec doesn't distinguish, and
     * the message text disambiguates for the human reader.
     *
     * <p><b>Why pass {@code projectId} explicitly</b> instead of looking it
     * up from the ticket: on POST the ticket doesn't exist yet (the value
     * comes from the request body); on PATCH it does (read from the
     * loaded entity). The caller knows which one to pass; the helper
     * stays simple.
     */
    private void assertValidAssignee(Long assigneeId, Long projectId) {
        Optional<User> u = users.findById(assigneeId);
        if (u.isEmpty() || u.get().getRole() != Role.DEVELOPER) {
            throw new ValidationException(
                    ErrorCode.INVALID_ASSIGNEE,
                    "Assignee " + assigneeId + " is not a DEVELOPER (or does not exist).",
                    List.of(new ApiError.FieldIssue(
                            "assigneeId",
                            "must reference an existing user with role DEVELOPER")));
        }
        // Slice 13 D7 — membership check via the same workload query that
        // backs the auto-assigner + the /workload endpoint. One source of
        // truth for ADR 0007.
        if (!autoAssigner.candidateIdsFor(projectId).contains(assigneeId)) {
            throw new ValidationException(
                    ErrorCode.INVALID_ASSIGNEE,
                    "Assignee " + assigneeId + " is not a member of project " + projectId
                            + " (a member is the owner or has at least one ticket in the project).",
                    List.of(new ApiError.FieldIssue(
                            "assigneeId",
                            "must be a member of the target project (owner or has-a-ticket-here)")));
        }
    }
}

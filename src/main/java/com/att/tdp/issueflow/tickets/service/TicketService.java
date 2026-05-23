package com.att.tdp.issueflow.tickets.service;

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
 * <p>Implements spec 04 §1–§12 except the parts owned by later slices:
 * <ul>
 *   <li>§4 auto-assignment when {@code assigneeId} is omitted — slice 13.
 *       Until then, omitted {@code assigneeId} stays {@code null}; a TODO
 *       marker is in {@link #create} where the auto-assigner plugs in.</li>
 *   <li>§4 "DEVELOPER active in that project" check — slice 13 (project
 *       membership). For now, we validate only the role half: assignee must
 *       exist and have {@link Role#DEVELOPER}. Session-05 D1 / prompts.md.</li>
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
        // §3: project must exist (and not be soft-deleted, which @SQLRestriction
        // handles automatically once slice 9 lands).
        if (!projects.existsById(req.projectId())) {
            throw new NotFoundException(
                    ErrorCode.PROJECT_NOT_FOUND,
                    "Project " + req.projectId() + " was not found.");
        }
        // §4 (partial): if supplied, validate exists + DEVELOPER role.
        if (req.assigneeId() != null) {
            assertValidAssignee(req.assigneeId());
        }
        // TODO(slice-13): if req.assigneeId() == null, invoke AutoAssigner here.
        // Until then, leave null and rely on a later PATCH to set the assignee.

        Ticket t = new Ticket();
        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setStatus(req.status() != null ? req.status() : TicketStatus.TODO); // §5 default
        t.setPriority(req.priority());
        t.setType(req.type());
        t.setProjectId(req.projectId());
        t.setAssigneeId(req.assigneeId());
        t.setDueDate(req.dueDate());
        t.setOverdue(false);    // §5: isOverdue:false on create
        Ticket saved = tickets.save(t); // @Version starts at 0 on insert
        auditLog.log(AuditAction.CREATE, EntityType.TICKET, saved.getId());
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

        // Reassignment (Session-05 D2): same rules as on create.
        if (req.assigneeId() != null) {
            assertValidAssignee(req.assigneeId());
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
        tickets.deleteById(id); // slice 9 converts to soft delete via @SQLDelete
        auditLog.log(AuditAction.DELETE, EntityType.TICKET, id);
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * §4 (partial — Session-05 D1): assignee must exist and have the DEVELOPER role.
     * The "active in that project" half is deferred to slice 13 (project membership).
     */
    private void assertValidAssignee(Long assigneeId) {
        Optional<User> u = users.findById(assigneeId);
        if (u.isEmpty() || u.get().getRole() != Role.DEVELOPER) {
            throw new ValidationException(
                    ErrorCode.INVALID_ASSIGNEE,
                    "Assignee " + assigneeId + " is not a DEVELOPER (or does not exist).",
                    List.of(new ApiError.FieldIssue(
                            "assigneeId",
                            "must reference an existing user with role DEVELOPER")));
        }
    }
}

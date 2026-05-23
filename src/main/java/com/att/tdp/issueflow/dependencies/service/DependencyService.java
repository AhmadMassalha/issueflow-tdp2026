package com.att.tdp.issueflow.dependencies.service;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.dependencies.domain.TicketDependency;
import com.att.tdp.issueflow.dependencies.repository.BlockerSummary;
import com.att.tdp.issueflow.dependencies.repository.TicketDependencyRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for ticket-blocker dependencies (spec 07).
 *
 * <p><b>Why this service exists separately from {@code TicketService}:</b>
 * spec 04 §9 needs {@code TicketService.update(...)} to ask "are there
 * open blockers?" when transitioning to DONE — but the dependency CRUD
 * is logically its own feature module (own controller, own DTOs, own
 * tests). The DI graph stays acyclic because {@code DependencyService}
 * reads tickets via {@code TicketRepository} rather than calling back
 * into {@code TicketService} (Session 08 D4).
 *
 * <p><b>Validation order in {@link #add(Long, Long)}</b> (Session 08 D7,
 * each step asserted in {@code DependencyServiceTest} as the FIRST
 * exception fired for an input hitting multiple checks):
 * <ol>
 *   <li>{@code ticketId == blockerId} → {@code DEPENDENCY_SELF} (422)</li>
 *   <li>Both tickets exist → {@code TICKET_NOT_FOUND} (404)</li>
 *   <li>Same project → {@code DEPENDENCY_DIFFERENT_PROJECT} (422)</li>
 *   <li>Pair already exists → {@code DEPENDENCY_EXISTS} (409)</li>
 *   <li>Cycle introduced → {@code DEPENDENCY_CYCLE} (422) — BFS from
 *       {@code blockerId}, depth-cap 100 (Session 08 D2)</li>
 * </ol>
 *
 * <p><b>Cycle detection BFS:</b> we ask "if I add {@code ticketId} →
 * {@code blockerId}, can I reach {@code ticketId} by following blockers
 * from {@code blockerId}?". If yes, the new edge would close a cycle.
 * Implementation: standard BFS over the blocker graph starting at
 * {@code blockerId}, batched one repository call per level
 * ({@link TicketDependencyRepository#findBlockerIdsByTicketIds}). The
 * 100-level cap is a safety belt (matching the spec hint) — a real graph
 * deeper than 100 is almost certainly malicious or buggy.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DependencyService {

    /** Spec §4: "max depth (e.g. 100)". A real human-built graph rarely exceeds 5. */
    static final int MAX_BFS_DEPTH = 100;

    private final TicketDependencyRepository dependencies;
    private final TicketRepository tickets;
    private final AuditLogService auditLog;

    // ---- queries ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BlockerSummary> listBlockers(Long ticketId) {
        assertTicketExists(ticketId);
        return dependencies.findBlockerSummariesByTicketId(ticketId);
    }

    // ---- cross-feature (spec 04 §9) ----------------------------------------

    /**
     * Cross-feature hook called by {@code TicketService.update(...)} when a
     * client requests transition to DONE. Returns {@code true} if at least
     * one blocker is still non-DONE (transition must be rejected with
     * {@code TICKET_HAS_OPEN_BLOCKERS}). {@code false} when the ticket has
     * no blockers at all, or all blockers are already DONE.
     */
    @Transactional(readOnly = true)
    public boolean hasOpenBlockers(Long ticketId) {
        return dependencies.countOpenBlockers(ticketId) > 0;
    }

    // ---- add ----------------------------------------------------------------

    public TicketDependency add(Long ticketId, Long blockerId) {
        // (1) Self-dependency — cheapest, no DB hit.
        if (ticketId.equals(blockerId)) {
            throw new ValidationException(
                    ErrorCode.DEPENDENCY_SELF,
                    "A ticket cannot block itself.",
                    List.of(new ApiError.FieldIssue(
                            "blockedBy", "must reference a different ticket id")));
        }

        // (2) Both tickets exist. Two distinct 404s with the right id in the
        //     message — the client knows which one is missing.
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.TICKET_NOT_FOUND, "Ticket " + ticketId + " was not found."));
        Ticket blocker = tickets.findById(blockerId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.TICKET_NOT_FOUND, "Ticket " + blockerId + " was not found."));

        // (3) Same project. Cross-project dependencies are explicitly rejected
        //     by the spec — they'd let one project's release block another's.
        if (!ticket.getProjectId().equals(blocker.getProjectId())) {
            throw new ValidationException(
                    ErrorCode.DEPENDENCY_DIFFERENT_PROJECT,
                    "Both tickets must belong to the same project.",
                    List.of(new ApiError.FieldIssue(
                            "blockedBy",
                            "ticket " + blockerId + " is in project " + blocker.getProjectId()
                                    + ", not " + ticket.getProjectId())));
        }

        // (4) Duplicate. Cheap derived-query existence check before the BFS.
        if (dependencies.existsByTicketIdAndBlockerId(ticketId, blockerId)) {
            throw new ConflictException(
                    ErrorCode.DEPENDENCY_EXISTS,
                    "Ticket " + ticketId + " is already blocked by ticket " + blockerId + ".");
        }

        // (5) Cycle. Expensive (BFS). Last to run.
        assertNoCycle(ticketId, blockerId);

        TicketDependency dep = new TicketDependency();
        dep.setTicketId(ticketId);
        dep.setBlockerId(blockerId);
        TicketDependency saved = dependencies.save(dep);

        auditLog.log(AuditAction.CREATE, EntityType.DEPENDENCY, saved.getId());
        return saved;
    }

    // ---- delete -------------------------------------------------------------

    /**
     * Spec §6 + Session 08 D8: tenancy via {@code findByTicketIdAndBlockerId}.
     * A DELETE against the wrong ticket URL is indistinguishable from "doesn't
     * exist" — same anti-information-leak rule as comment delete (slice 6).
     */
    public void remove(Long ticketId, Long blockerId) {
        TicketDependency dep = dependencies.findByTicketIdAndBlockerId(ticketId, blockerId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.DEPENDENCY_NOT_FOUND,
                        "Ticket " + ticketId + " is not blocked by ticket " + blockerId + "."));
        Long depId = dep.getId(); // capture BEFORE delete — entity is gone after
        dependencies.delete(dep);
        auditLog.log(AuditAction.DELETE, EntityType.DEPENDENCY, depId);
    }

    // ---- helpers ------------------------------------------------------------

    private void assertTicketExists(Long ticketId) {
        if (!tickets.existsById(ticketId)) {
            throw new NotFoundException(
                    ErrorCode.TICKET_NOT_FOUND, "Ticket " + ticketId + " was not found.");
        }
    }

    /**
     * BFS over the blocker graph starting at {@code blockerId}. If we
     * encounter {@code ticketId} before exhausting the graph, adding the
     * proposed edge would close a cycle.
     *
     * <p>Batched per level: at each step we ask the repository "give me
     * every blocker of every ticket currently in the frontier" — one
     * {@code IN (...)} query per level, not per node. A 50-deep linear
     * chain is 50 queries, not 50² (which would be the naive recursive
     * version).
     *
     * <p>The {@link #MAX_BFS_DEPTH} cap is a safety belt. Stack-safe
     * (iterative loop, not recursive) so even a malformed graph won't
     * crash the JVM.
     */
    private void assertNoCycle(Long ticketId, Long blockerId) {
        Set<Long> visited = new HashSet<>();
        List<Long> frontier = new ArrayList<>();
        frontier.add(blockerId);
        visited.add(blockerId);

        for (int depth = 0; depth < MAX_BFS_DEPTH && !frontier.isEmpty(); depth++) {
            List<Long> nextBlockers = dependencies.findBlockerIdsByTicketIds(frontier);
            if (nextBlockers.isEmpty()) {
                return; // graph exhausted, no cycle reachable
            }
            // If ticketId is reachable from blockerId via the existing graph,
            // the proposed new edge ticketId -> blockerId closes a cycle.
            if (nextBlockers.contains(ticketId)) {
                throw new ValidationException(
                        ErrorCode.DEPENDENCY_CYCLE,
                        "Adding this dependency would create a cycle.",
                        List.of(new ApiError.FieldIssue(
                                "blockedBy",
                                "ticket " + blockerId + " (transitively) depends on ticket " + ticketId)));
            }
            // Build next frontier from "blockers we haven't visited yet".
            List<Long> nextFrontier = new ArrayList<>();
            for (Long id : nextBlockers) {
                if (visited.add(id)) {
                    nextFrontier.add(id);
                }
            }
            frontier = nextFrontier;
        }
        // Loop ended because frontier emptied (no cycle) OR we hit the cap.
        // Hitting the cap on a real human graph is essentially impossible;
        // we treat it as "no cycle found within the safety horizon" and
        // allow the insert. The alternative (rejecting at the cap) would
        // break edge cases for legitimate-but-deep graphs.
    }
}

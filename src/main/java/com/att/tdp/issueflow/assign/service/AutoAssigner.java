package com.att.tdp.issueflow.assign.service;

import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-assignment by workload (spec 12 §Algorithm).
 *
 * <p>Picks the {@code DEVELOPER} project member with the lowest count of open
 * tickets in the given project; ties broken by lowest user id. Both ordering
 * dimensions are done in SQL by
 * {@link UserRepository#findWorkloadForProject(Long)} — this class just
 * picks the head of the spec-sorted list.
 *
 * <p><b>Member definition</b> is owned by ADR 0007 (Session 13 D1): owner ∪
 * users-with-tickets, intersected with {@code role = DEVELOPER}. The
 * repository query encodes both halves; this service doesn't re-implement
 * the predicate.
 *
 * <p><b>Two consumers, one query:</b>
 * <ul>
 *   <li>{@link #pickAssignee(Long)} — called by {@code TicketService.create()}
 *       when the request omits {@code assigneeId}. Returns the head of the
 *       workload list, or {@code Optional.empty()} if the project has no
 *       eligible developers (spec §Algorithm point 4 — falls through to
 *       {@code assigneeId = null}; the caller does NOT raise).</li>
 *   <li>{@link #candidateIdsFor(Long)} — used by {@code TicketService}'s
 *       slice-13 D7 tightening: a supplied {@code assigneeId} must be in
 *       the member set, otherwise INVALID_ASSIGNEE. Returns the set of
 *       member user ids (NOT the workload counts, just the eligibility
 *       check).</li>
 * </ul>
 *
 * <p><b>Transactional propagation:</b> {@code SUPPORTS} (read-only,
 * participates in the caller's tx if one exists; runs without a tx if
 * called outside one — defensive default). The wrapping
 * {@code TicketService.create()} is {@code @Transactional}, so in normal
 * operation we inherit its REQUIRED tx and the workload query runs
 * inside it — same tx as the eventual save + audit row, per spec §4.
 */
@Service
@RequiredArgsConstructor
public class AutoAssigner {

    private final UserRepository users;

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<Long> pickAssignee(Long projectId) {
        List<WorkloadEntry> workload = users.findWorkloadForProject(projectId);
        // Spec §Algorithm point 3: ORDER BY count ASC, userId ASC is already
        // done by the JPQL — head of list IS the chosen assignee.
        // Spec §Algorithm point 4: empty list → return empty Optional. The
        // caller (TicketService.create) interprets this as "set assigneeId
        // = null, do not raise."
        return workload.isEmpty()
                ? Optional.empty()
                : Optional.of(workload.get(0).userId());
    }

    /**
     * Set of user ids eligible to be assignees on tickets in this project
     * (per ADR 0007). Used by {@code TicketService.assertValidAssignee} to
     * enforce the spec-04 §4 "active in that project" requirement that
     * Session 05 D1 deferred — now closed via this slice's D7.
     *
     * <p>Implementation reuses the workload query (one SQL trip whether the
     * caller wants counts or just the ids — counts are computed regardless
     * because the {@code group by} doesn't change the cost meaningfully
     * for the small membership sets in this app). The alternative — a
     * dedicated {@code findCandidateIds} JPQL — would duplicate the
     * membership predicate and create a drift risk between the two
     * queries when ADR 0007 ever changes.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Set<Long> candidateIdsFor(Long projectId) {
        return users.findWorkloadForProject(projectId).stream()
                .map(WorkloadEntry::userId)
                .collect(Collectors.toUnmodifiableSet());
    }
}

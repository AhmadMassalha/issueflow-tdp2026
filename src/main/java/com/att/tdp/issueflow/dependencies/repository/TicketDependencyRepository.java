package com.att.tdp.issueflow.dependencies.repository;

import com.att.tdp.issueflow.dependencies.domain.TicketDependency;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link TicketDependency}.
 *
 * <p>Three query patterns:
 * <ul>
 *   <li>{@link #findByTicketId(Long)} — list raw edges for a ticket. Used
 *       by {@code DELETE} validation and by the audit-aware delete path.</li>
 *   <li>{@link #findByTicketIdAndBlockerId(Long, Long)} — composite lookup
 *       used for tenancy + duplicate detection + delete-by-pair
 *       (Session 08 D8, same idiom as slice-6 comment tenancy).</li>
 *   <li>{@link #findBlockerSummariesByTicketId(Long)} — the
 *       {@code GET /tickets/{tid}/dependencies} happy path. Single JPQL
 *       JOIN to {@code Ticket} returning a projection record (Session 08
 *       D5).</li>
 *   <li>{@link #findBlockerIdsByTicketIds(java.util.Collection)} — BFS-per-
 *       level batched fetch used by cycle detection. Single
 *       {@code IN (...)} query per level, so a cycle check on a 50-deep
 *       linear chain is 50 queries, not 50² (Session 08 D2).</li>
 *   <li>{@link #countOpenBlockers(Long)} — the cross-feature DONE-blocker
 *       check called from {@code TicketService.update(...)} (spec 04 §9,
 *       Session 08 D3). Returns 0 when the ticket has no non-DONE
 *       blockers (transition allowed) or &gt;0 otherwise.</li>
 * </ul>
 */
public interface TicketDependencyRepository extends JpaRepository<TicketDependency, Long> {

    List<TicketDependency> findByTicketId(Long ticketId);

    Optional<TicketDependency> findByTicketIdAndBlockerId(Long ticketId, Long blockerId);

    boolean existsByTicketIdAndBlockerId(Long ticketId, Long blockerId);

    @Query("""
           select new com.att.tdp.issueflow.dependencies.repository.BlockerSummary(
               t.id, t.title, t.status)
           from TicketDependency d
             join com.att.tdp.issueflow.tickets.domain.Ticket t on t.id = d.blockerId
           where d.ticketId = :ticketId
           order by t.id asc
           """)
    List<BlockerSummary> findBlockerSummariesByTicketId(@Param("ticketId") Long ticketId);

    /**
     * BFS batching helper: given a frontier of "tickets we're currently
     * walking", return the union of their blockers in one shot. Used by
     * {@code DependencyService.assertNoCycle}.
     */
    @Query("select d.blockerId from TicketDependency d where d.ticketId in :ticketIds")
    List<Long> findBlockerIdsByTicketIds(@Param("ticketIds") java.util.Collection<Long> ticketIds);

    /**
     * Count blockers of {@code ticketId} whose status is NOT DONE. Backs
     * the cross-feature {@code TicketService.update(...)} DONE guard
     * (spec 04 §9). Returns {@code long} not {@code boolean} so callers
     * can branch on "&gt; 0" without an additional query for the count.
     */
    @Query("""
           select count(d)
           from TicketDependency d
             join com.att.tdp.issueflow.tickets.domain.Ticket t on t.id = d.blockerId
           where d.ticketId = :ticketId
             and t.status <> com.att.tdp.issueflow.common.enums.TicketStatus.DONE
           """)
    long countOpenBlockers(@Param("ticketId") Long ticketId);
}

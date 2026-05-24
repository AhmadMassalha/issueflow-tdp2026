package com.att.tdp.issueflow.tickets.repository;

import com.att.tdp.issueflow.tickets.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

/**
 * Persistence boundary for {@link Ticket}.
 *
 * <p>{@code findByProjectId} + the inherited {@code findById} / {@code findAll}
 * etc. are all filtered by {@link Ticket}'s {@code @SQLRestriction} —
 * soft-deleted rows are invisible to every JPQL/HQL/derived query (Session 09
 * D5).
 *
 * <p><b>Native bypass methods (slice 9, spec 08):</b>
 * <ul>
 *   <li>{@link #findDeletedByProjectId(Long)} — the
 *       {@code GET /tickets/deleted?projectId=...} admin endpoint.</li>
 *   <li>{@link #existsByIdIncludingDeleted(Long)} — the "does this row
 *       physically exist, deleted or not?" gate that lets the service
 *       distinguish 404 (truly missing) from 409 ({@code ALREADY_ACTIVE},
 *       the row is here and active so restore is a no-op).</li>
 *   <li>{@link #restoreById(Long)} — atomic restore. Sets {@code deleted_at
 *       = NULL} and bumps {@code version} explicitly (native bypasses
 *       {@code @Version}). Returns the number of affected rows so callers
 *       can detect the rare race-window "someone restored before me".</li>
 * </ul>
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByProjectId(Long projectId);

    @Query(value = "SELECT * FROM tickets WHERE deleted_at IS NOT NULL AND project_id = :projectId ORDER BY id ASC",
            nativeQuery = true)
    List<Ticket> findDeletedByProjectId(@Param("projectId") Long projectId);

    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM tickets WHERE id = :id",
            nativeQuery = true)
    boolean existsByIdIncludingDeleted(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE tickets SET deleted_at = NULL, version = version + 1 "
            + "WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /**
     * Cursor-based stream of all (non-soft-deleted) tickets in a project,
     * ordered by id ascending. Used by {@code CsvExportService} to write
     * the export response without buffering the full result set in memory
     * (spec 10 §4 — Session 11 D6).
     *
     * <p>The {@link Stream} <b>MUST</b> be consumed inside the same
     * {@code @Transactional} as the call and closed (try-with-resources)
     * — Hibernate keeps the JDBC cursor open for the duration of the
     * stream, and Spring releases the underlying connection only when
     * either the stream is closed or the transaction commits. The
     * {@code FETCH_SIZE} hint asks JDBC to fetch in batches rather than
     * pulling the whole result set up-front (effective on PostgreSQL when
     * autocommit is off — which it always is inside a Spring transaction).
     */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "256"))
    @Query("SELECT t FROM Ticket t WHERE t.projectId = :projectId ORDER BY t.id ASC")
    Stream<Ticket> streamByProjectIdOrderByIdAsc(@Param("projectId") Long projectId);

    /**
     * Slice 14 — overdue tickets considered for escalation (spec 13
     * §Algorithm point 1).
     *
     * <p>Filter: {@code dueDate != null}, {@code dueDate < now},
     * {@code status != DONE}. The {@code deleted_at IS NULL} half of the
     * spec's WHERE clause is satisfied automatically by the
     * {@code @SQLRestriction} on {@link Ticket} (slice 9) — JPQL respects
     * it, so we don't repeat it here.
     *
     * <p>ORDER BY id ASC for (a) deterministic test assertions on
     * audit-row ordering, (b) deterministic processing order in the
     * scheduler so a partial batch failure is reproducible.
     *
     * <p>Returns a plain {@code List} (not a {@code Stream}) — the
     * candidate set is bounded by active overdue tickets, usually a
     * small number; batching/cursor semantics aren't worth the
     * complexity for this slice.
     *
     * @param now the current instant. Injected from the {@code Clock}
     *            bean by the caller (slice 14 D1) so tests can fix the
     *            time without seeding tickets relative to wall-clock.
     */
    @Query("""
           SELECT t FROM Ticket t
            WHERE t.dueDate IS NOT NULL
              AND t.dueDate < :now
              AND t.status <> com.att.tdp.issueflow.common.enums.TicketStatus.DONE
            ORDER BY t.id ASC
           """)
    List<Ticket> findOverdueForEscalation(@Param("now") Instant now);
}

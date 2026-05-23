package com.att.tdp.issueflow.tickets.repository;

import com.att.tdp.issueflow.tickets.domain.Ticket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

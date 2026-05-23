package com.att.tdp.issueflow.dependencies.domain;

import com.att.tdp.issueflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Join entity for {@code ticket -> blocker} dependency edges (spec 07).
 *
 * <p><b>Why surrogate id + unique constraint instead of a composite key?</b>
 * Session 08 D1: the spec asks audit rows to point at this entity via
 * {@code AuditLog.entityId} (a {@code Long} column). A composite-key entity
 * would force lossy encoding to a single {@code Long}, or {@code null} +
 * a string in {@code diff}. The surrogate id sidesteps both problems
 * without compromising uniqueness, which is still enforced by the DB-level
 * {@link UniqueConstraint} on {@code (ticket_id, blocker_id)}.
 *
 * <p>Both FKs are stored as plain {@code Long}s (same convention as
 * {@code Ticket.projectId}, {@code Ticket.assigneeId} — Session 04/05).
 * No {@code @ManyToOne} traversal needed; queries either go via the
 * repository or via JPQL JOINs (see {@code BlockerSummary}).
 *
 * <p>No {@code @Version} — dependencies aren't editable (you delete + re-
 * add). No {@code deletedAt} — slice 9 owns soft-delete and dependencies
 * are scoped per ticket; when a ticket is soft-deleted, its dependency
 * edges become orphaned-but-filtered via the same {@code @SQLRestriction}
 * cascade pattern (slice 9 owns the actual wiring).
 *
 * <p>Indexes: the {@code (ticket_id, blocker_id)} unique constraint
 * doubles as the {@code ticket_id}-prefix index needed by
 * {@code findByTicketId} (most-common query path: list a ticket's
 * blockers). A separate {@code blocker_id} index supports the reverse
 * lookup used by cycle detection's BFS.
 */
@Entity
@Table(
        name = "ticket_dependencies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ticket_dependency_pair",
                columnNames = {"ticket_id", "blocker_id"}
        ),
        indexes = @Index(name = "idx_ticket_dependency_blocker", columnList = "blocker_id")
)
@Getter
@Setter
@NoArgsConstructor
public class TicketDependency extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "blocker_id", nullable = false)
    private Long blockerId;
}

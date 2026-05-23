package com.att.tdp.issueflow.projects.domain;

import com.att.tdp.issueflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Project — a top-level container that tickets (slice 5) will belong to.
 *
 * <p><b>Owner relationship:</b> {@code ownerId} is a plain {@code Long}, not a
 * {@code @ManyToOne User}. The response DTO is flat ({@code {id, name,
 * description, ownerId}}) per spec 03 + the README, so dragging in a JOIN
 * across every project read would buy us nothing. {@link
 * com.att.tdp.issueflow.projects.service.ProjectService#create} validates the
 * owner exists by id before insert so the FK-style guarantee is preserved at
 * the application layer.
 *
 * <p><b>Unique constraint:</b> {@code (owner_id, name)} — same name allowed
 * across different owners (spec 03 §2). Pre-emptive check lives in the service
 * to return the spec'd {@code PROJECT_DUPLICATE_NAME}; the DB constraint is
 * the safety net for the race (same pattern as {@link
 * com.att.tdp.issueflow.users.domain.User}).
 *
 * <p><b>Soft delete (slice 9, spec 08 + ADR 0002):</b>
 * <ul>
 *   <li>{@link SQLDelete} replaces Hibernate's generated DELETE with an
 *       UPDATE setting {@code deleted_at = NOW()}. Existing
 *       {@code repo.deleteById(id)} callers are unchanged.</li>
 *   <li>{@link SQLRestriction} hides soft-deleted rows from every JPQL/HQL/
 *       derived-query SELECT. ADMIN-only {@code GET /projects/deleted}
 *       bypasses via a native query.</li>
 *   <li>Restore via {@code POST /projects/{id}/restore} — native
 *       {@code @Modifying} UPDATE because {@code findById} would be filtered
 *       out. No {@code version} column on this entity (spec 03 doesn't
 *       require optimistic locking for projects); {@link
 *       com.att.tdp.issueflow.tickets.domain.Ticket} DOES bump version in
 *       its restore.</li>
 *   <li>Per ADR 0002: project soft-delete does NOT cascade to tickets.
 *       Restoring a project leaves its tickets' state untouched.</li>
 * </ul>
 */
@Entity
@Table(
        name = "projects",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_projects_owner_name", columnNames = {"owner_id", "name"})
        }
)
@SQLDelete(sql = "UPDATE projects SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Project extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /**
     * Soft-delete timestamp slot. Always {@code null} until slice 9 turns on
     * the soft-delete behaviour. See class JavaDoc + ADR 0002.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}

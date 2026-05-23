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
 * <p><b>Soft delete:</b> {@code deletedAt} is declared here but not yet
 * exercised. Slice 9 wires {@code @SQLDelete} + {@code @SQLRestriction} on
 * every soft-delete-capable entity in one pass per ADR 0002. Until then,
 * {@code DELETE /projects/{id}} is a hard delete (spec 03 §5 permits this).
 */
@Entity
@Table(
        name = "projects",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_projects_owner_name", columnNames = {"owner_id", "name"})
        }
)
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

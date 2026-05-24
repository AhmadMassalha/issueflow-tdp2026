package com.att.tdp.issueflow.tickets.domain;

import com.att.tdp.issueflow.common.entity.BaseEntity;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Ticket — the central work item.
 *
 * <p><b>Foreign keys as plain {@code Long}:</b> {@code projectId} and
 * {@code assigneeId} are stored as ids, not {@code @ManyToOne} associations.
 * Same rationale as {@link com.att.tdp.issueflow.projects.domain.Project} —
 * keeps the response projection cheap and avoids dragging JOINs into every
 * list. Existence and role checks are enforced at the service layer
 * ({@code TicketService}). Slice 13 will revisit if the auto-assigner needs
 * a JOIN to filter "active in project" developers.
 *
 * <p><b>Optimistic locking ({@code @Version}, ADR 0001):</b> the {@code version}
 * field is incremented by Hibernate on every UPDATE. Spec 04 §6 requires
 * clients to echo the loaded version back in PATCHes; missing → 400
 * {@code VERSION_REQUIRED}, stale → 409 {@code TICKET_VERSION_CONFLICT}. The
 * version check is implemented twice (defense in depth):
 * <ol>
 *   <li>A fast pre-check in {@code TicketService.update} compares the client's
 *       version against the loaded entity's version.</li>
 *   <li>{@link com.att.tdp.issueflow.common.web.GlobalExceptionHandler#handleOptimisticLock}
 *       catches the race-window failure that Hibernate raises at flush time
 *       and emits the same {@code TICKET_VERSION_CONFLICT} code.</li>
 * </ol>
 *
 * <p><b>{@code isOverdue}:</b> declared in slice 5. Slice 14's
 * {@code EscalationService} sets it to {@code true} on CRITICAL tickets
 * that remain overdue (spec 13 §Algorithm point 3) — idempotently, only
 * when the value is actually changing. Manual priority changes via PATCH
 * reset {@code isOverdue} to {@code false} per spec 04 §10, implemented
 * in {@code TicketService.update} (slice 5 D6 — designed with slice 14's
 * "reset the cycle" contract in mind, validated in slice 14's
 * integration test).
 *
 * <p><b>Soft delete (slice 9, spec 08 + ADR 0002):</b>
 * <ul>
 *   <li>{@link SQLDelete} replaces Hibernate's generated {@code DELETE FROM
 *       tickets WHERE id=?} with an UPDATE that sets {@code deleted_at = NOW()}.
 *       The application's {@code repo.delete(ticket)} / {@code repo.deleteById(id)}
 *       calls continue to work unchanged — Hibernate intercepts at SQL
 *       generation time.</li>
 *   <li>{@link SQLRestriction} appends {@code AND deleted_at IS NULL} to
 *       every JPQL/HQL/derived-query SELECT against this entity. Net effect:
 *       once a row is soft-deleted, every existing read path (GET, PATCH,
 *       LIST, JOINs from comments + dependencies) treats it as if it doesn't
 *       exist. This is the desired behavior; the ADMIN-only {@code GET
 *       /tickets/deleted} bypasses via a {@code nativeQuery=true} repo
 *       method. See Session 09 D2/D5.</li>
 *   <li>The restore path ({@code POST /tickets/{id}/restore}) is also native
 *       ({@code @Modifying} UPDATE) because {@code findById(deletedId)} would
 *       be filtered out by the restriction. Session 09 D3.</li>
 *   <li>Per ADR 0002 — soft-delete does NOT cascade from project to ticket.
 *       Tickets keep their {@code project_id} pointing at the (hidden)
 *       parent.</li>
 * </ul>
 */
@Entity
@Table(name = "tickets")
// Two-placeholder SQL: Hibernate binds (id, version) in that order because of
// @Version. A single-? SQL throws H2 error 90008 "Invalid value 2 for parameter
// parameterIndex" at delete time. See .cursor/rules/30-testing.mdc Gotcha:
// "@SQLDelete + @Version requires both placeholders in the custom SQL."
@SQLDelete(sql = "UPDATE tickets SET deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Ticket extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketType type;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "is_overdue", nullable = false)
    private boolean isOverdue = false;

    /**
     * JPA optimistic-locking version. Hibernate increments on every UPDATE
     * and uses it in the UPDATE … WHERE clause; mismatches surface as
     * {@code ObjectOptimisticLockingFailureException}.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    /** Soft-delete slot. Always {@code null} until slice 9 wires the behaviour. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}

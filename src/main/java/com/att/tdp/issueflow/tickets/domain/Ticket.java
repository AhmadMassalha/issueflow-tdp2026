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
 * <p><b>{@code isOverdue} / {@code deletedAt}:</b> declared here but not
 * actively mutated by this slice. Slice 9 enables {@code deletedAt} via
 * {@code @SQLDelete} + {@code @SQLRestriction}; slice 14 (escalation) toggles
 * {@code isOverdue} from the scheduler. Manual priority changes via PATCH
 * reset {@code isOverdue} to {@code false} per spec 04 §10, implemented in
 * {@code TicketService.update}.
 */
@Entity
@Table(name = "tickets")
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

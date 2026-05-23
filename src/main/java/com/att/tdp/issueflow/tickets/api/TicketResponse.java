package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import java.time.Instant;

/**
 * Response body for every {@code /tickets/**} endpoint.
 *
 * <p>Spec 04 §5: "response includes all fields including {@code isOverdue:false}
 * and {@code version:0}." Both are included by this DTO. Audit timestamps
 * are exposed (unlike the slice-4 {@link com.att.tdp.issueflow.projects.api.ProjectResponse}
 * which omits them) because clients need them for staleness / sorting on the
 * board view referenced in the README.
 *
 * <p>Foreign keys are kept as ids — no expanded {@code project} or
 * {@code assignee} object — consistent with the entity-design choice
 * documented in {@link Ticket}.
 *
 * <p><b>Slice 9 — {@code deletedAt}:</b> nullable. {@code null} for every
 * active ticket (the {@code @SQLRestriction} hides deleted rows from all
 * standard read paths, so 99% of responses serialize this field as
 * {@code null}). Populated only when the admin-only {@code GET
 * /tickets/deleted?projectId=...} surfaces a soft-deleted ticket. Session
 * 09 D6.
 */
public record TicketResponse(
        Long id,
        String title,
        String description,
        TicketStatus status,
        Priority priority,
        TicketType type,
        Long projectId,
        Long assigneeId,
        Instant dueDate,
        boolean isOverdue,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getPriority(),
                t.getType(),
                t.getProjectId(),
                t.getAssigneeId(),
                t.getDueDate(),
                t.isOverdue(),
                t.getVersion(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getDeletedAt()
        );
    }
}

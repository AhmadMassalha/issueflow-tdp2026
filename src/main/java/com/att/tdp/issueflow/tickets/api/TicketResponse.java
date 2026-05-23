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
        Instant updatedAt
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
                t.getUpdatedAt()
        );
    }
}

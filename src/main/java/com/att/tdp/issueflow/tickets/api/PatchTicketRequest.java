package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Request body for {@code PATCH /tickets/{ticketId}}.
 *
 * <p><b>Semantics (Session-05 D2/D3/D4):</b>
 * <ul>
 *   <li>All editable fields are nullable. {@code null} → unchanged. Empty
 *       string on {@code title}/{@code description} → overwrite (allow clear).</li>
 *   <li>{@code version} is also nullable in the DTO, BUT the service treats
 *       absence as {@code VERSION_REQUIRED} (400) per spec 04 §6. Doing the
 *       check in the service (not as {@code @NotNull}) gives us the
 *       feature-specific code without coupling Bean Validation to a custom
 *       error path.</li>
 *   <li>Status transitions are validated against the FSM (spec 04 §7): only
 *       the strict next step is allowed; no-op (status = current) is
 *       intentionally accepted per Session-05 D4.</li>
 *   <li>Manual {@code priority} change clears the {@code isOverdue} flag
 *       (spec 04 §10) — implemented in the service.</li>
 * </ul>
 *
 * <p>{@code isOverdue} and {@code projectId} are intentionally NOT exposed
 * here — the former is escalation-owned, the latter is structural (move-to-
 * other-project is not in any spec). Fields not declared on this DTO are
 * silently ignored by Jackson, same convention as
 * {@link com.att.tdp.issueflow.projects.api.PatchProjectRequest}.
 */
public record PatchTicketRequest(
        @Size(max = 200)
        String title,

        @Size(max = 50_000)
        String description,

        @JsonDeserialize(using = TicketStatusJsonDeserializer.class)
        TicketStatus status,

        @JsonDeserialize(using = PriorityJsonDeserializer.class)
        Priority priority,

        @JsonDeserialize(using = TicketTypeJsonDeserializer.class)
        TicketType type,

        @Positive
        Long assigneeId,

        Instant dueDate,

        Long version
) {}

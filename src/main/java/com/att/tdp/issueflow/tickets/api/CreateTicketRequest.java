package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Request body for {@code POST /tickets}.
 *
 * <p>{@code status} is optional — when omitted the service defaults to
 * {@link TicketStatus#TODO} (spec 04 entity table). {@code assigneeId} is
 * optional and (per Session-05 D1) validated as "exists AND DEVELOPER" when
 * supplied; when omitted, slice 13's auto-assigner will fill it in. Until
 * slice 13 ships, omitted {@code assigneeId} stays {@code null}.
 *
 * <p>Three enum deserializers are wired so unknown values produce
 * feature-specific 400 codes ({@code TICKET_INVALID_*}) with a {@code details[]}
 * entry pointing at the offending field — spec 04 §2.
 */
public record CreateTicketRequest(
        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 50_000)
        String description,

        @JsonDeserialize(using = TicketStatusJsonDeserializer.class)
        TicketStatus status,

        @NotNull
        @JsonDeserialize(using = PriorityJsonDeserializer.class)
        Priority priority,

        @NotNull
        @JsonDeserialize(using = TicketTypeJsonDeserializer.class)
        TicketType type,

        @NotNull
        @Positive
        Long projectId,

        @Positive
        Long assigneeId,

        Instant dueDate
) {}

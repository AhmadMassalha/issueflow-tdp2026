package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps an incoming JSON {@code "priority"} string to a {@link Priority} enum;
 * unknown value → 400 {@link ErrorCode#TICKET_INVALID_PRIORITY} with
 * {@code details[{field:"priority", …}]}. Same shape as
 * {@link TicketStatusJsonDeserializer}; see that class for full rationale.
 */
public class PriorityJsonDeserializer extends JsonDeserializer<Priority> {

    @Override
    public Priority deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_PRIORITY,
                    "Priority is required.",
                    List.of(new ApiError.FieldIssue("priority", "must be one of " + allowed()))
            );
        }
        try {
            return Priority.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_PRIORITY,
                    "Unknown priority: " + raw,
                    List.of(new ApiError.FieldIssue("priority", "must be one of " + allowed()))
            );
        }
    }

    private static String allowed() {
        return Arrays.stream(Priority.values())
                .map(Enum::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

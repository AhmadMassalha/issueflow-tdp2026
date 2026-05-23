package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.TicketType;
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
 * Maps an incoming JSON {@code "type"} string to a {@link TicketType} enum;
 * unknown value → 400 {@link ErrorCode#TICKET_INVALID_TYPE} with
 * {@code details[{field:"type", …}]}. Same shape as
 * {@link TicketStatusJsonDeserializer}; see that class for full rationale.
 */
public class TicketTypeJsonDeserializer extends JsonDeserializer<TicketType> {

    @Override
    public TicketType deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_TYPE,
                    "Type is required.",
                    List.of(new ApiError.FieldIssue("type", "must be one of " + allowed()))
            );
        }
        try {
            return TicketType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_TYPE,
                    "Unknown type: " + raw,
                    List.of(new ApiError.FieldIssue("type", "must be one of " + allowed()))
            );
        }
    }

    private static String allowed() {
        return Arrays.stream(TicketType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

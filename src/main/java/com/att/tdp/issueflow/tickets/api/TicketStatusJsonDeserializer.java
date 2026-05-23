package com.att.tdp.issueflow.tickets.api;

import com.att.tdp.issueflow.common.enums.TicketStatus;
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
 * Maps an incoming JSON {@code "status"} string to a {@link TicketStatus}
 * enum, throwing {@link ValidationException} with
 * {@link ErrorCode#TICKET_INVALID_STATUS} (400) when the value is unknown.
 *
 * <p>Without this, Jackson raises {@code HttpMessageNotReadableException} →
 * generic {@code MALFORMED_REQUEST}. Spec 04 §2 wants the
 * {@code details[]} entry pointing at the field; mirroring the slice-2
 * {@code RoleJsonDeserializer} pattern keeps per-feature concerns inside the
 * per-feature package (Session-02 D4 carryover).
 */
public class TicketStatusJsonDeserializer extends JsonDeserializer<TicketStatus> {

    @Override
    public TicketStatus deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_STATUS,
                    "Status is required.",
                    List.of(new ApiError.FieldIssue("status", "must be one of " + allowed()))
            );
        }
        try {
            return TicketStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    ErrorCode.TICKET_INVALID_STATUS,
                    "Unknown status: " + raw,
                    List.of(new ApiError.FieldIssue("status", "must be one of " + allowed()))
            );
        }
    }

    private static String allowed() {
        return Arrays.stream(TicketStatus.values())
                .map(Enum::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

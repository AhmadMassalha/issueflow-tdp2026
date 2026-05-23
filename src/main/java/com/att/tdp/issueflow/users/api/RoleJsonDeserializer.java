package com.att.tdp.issueflow.users.api;

import com.att.tdp.issueflow.common.enums.Role;
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
 * Maps an incoming JSON {@code "role"} string to a {@link Role} enum, throwing
 * {@link ValidationException} with {@link ErrorCode#USER_INVALID_ROLE} when the
 * value is unknown.
 *
 * <p>Without this, Jackson raises {@code HttpMessageNotReadableException}, which
 * the global handler maps to the generic {@code MALFORMED_REQUEST} (400). Spec
 * 01 §3 demands the feature-specific {@code USER_INVALID_ROLE} code instead;
 * keeping the per-feature concern inside the per-feature package avoids
 * coupling the cross-cutting handler to a single error code (Session-02 D4).
 */
public class RoleJsonDeserializer extends JsonDeserializer<Role> {

    @Override
    public Role deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(
                    ErrorCode.USER_INVALID_ROLE,
                    "Role is required.",
                    List.of(new ApiError.FieldIssue("role", "must be one of " + allowedRoles()))
            );
        }
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    ErrorCode.USER_INVALID_ROLE,
                    "Unknown role: " + raw,
                    List.of(new ApiError.FieldIssue("role", "must be one of " + allowedRoles()))
            );
        }
    }

    private static String allowedRoles() {
        return Arrays.stream(Role.values())
                .map(Enum::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

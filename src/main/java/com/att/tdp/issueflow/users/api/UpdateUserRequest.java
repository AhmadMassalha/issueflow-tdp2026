package com.att.tdp.issueflow.users.api;

import com.att.tdp.issueflow.common.enums.Role;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /users/update/{userId}}.
 *
 * <p>Only {@code fullName} and {@code role} can be changed (spec 01 §6).
 * Fields not declared here (e.g. {@code username}, {@code email}, {@code password})
 * are silently ignored by Jackson — the spec explicitly requires this rather
 * than rejecting unknown fields with 400.
 */
public record UpdateUserRequest(
        @NotBlank
        @Size(max = 128)
        String fullName,

        @NotNull
        @JsonDeserialize(using = RoleJsonDeserializer.class)
        Role role
) {}

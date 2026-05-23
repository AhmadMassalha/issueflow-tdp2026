package com.att.tdp.issueflow.users.api;

import com.att.tdp.issueflow.common.enums.Role;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /users}.
 *
 * <p>{@code password} is plaintext on the wire (per Session-02 D1); the service
 * hashes with BCrypt before persist and the value is never echoed back. The DTO
 * is annotated as a record so the input is immutable and Jackson constructs it
 * via the canonical constructor.
 *
 * <p>{@code role} uses a feature-local Jackson deserializer that throws
 * {@link com.att.tdp.issueflow.common.exception.ValidationException} with
 * {@code USER_INVALID_ROLE} on an unknown value, per spec 01 §3.
 */
public record CreateUserRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$",
                message = "must be 3–32 chars of letters, digits, or underscore")
        String username,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(max = 128)
        String fullName,

        @NotNull
        @JsonDeserialize(using = RoleJsonDeserializer.class)
        Role role,

        @NotBlank
        @Size(min = 8, max = 128, message = "must be at least 8 characters")
        @Pattern(regexp = "^\\S+$", message = "must not contain whitespace")
        String password
) {}

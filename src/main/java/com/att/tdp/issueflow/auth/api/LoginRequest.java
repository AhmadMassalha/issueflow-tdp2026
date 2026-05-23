package com.att.tdp.issueflow.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /auth/login}.
 *
 * <p>Validation is intentionally loose — only NotBlank. We don't enforce the
 * username pattern or password size rules here so that a request shaped
 * {@code { "username":"alice","password":"secret" }} fails with
 * {@code AUTH_INVALID_CREDENTIALS}, not {@code VALIDATION_FAILED}. That
 * preserves the "indistinguishable timing / response shape" property the
 * spec calls for (spec 02 §2).
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {}

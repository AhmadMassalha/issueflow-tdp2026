package com.att.tdp.issueflow.auth.api;

/**
 * Response body for {@code POST /auth/login}.
 *
 * <p>Shape locked by spec 02 §1 / README: {@code { accessToken, tokenType, expiresIn }}.
 * {@code tokenType} is always {@code "Bearer"} for this assignment — no scheme
 * negotiation.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    public static LoginResponse bearer(String token, long expiresInSeconds) {
        return new LoginResponse(token, "Bearer", expiresInSeconds);
    }
}

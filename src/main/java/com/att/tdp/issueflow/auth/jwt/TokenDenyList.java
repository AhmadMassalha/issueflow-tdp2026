package com.att.tdp.issueflow.auth.jwt;

import java.time.Instant;

/**
 * Revocation registry for JWT {@code jti} claims (per ADR 0003).
 *
 * <p>The interface is intentionally minimal so the in-memory impl can be
 * swapped for a Redis-backed one in production without touching the filter,
 * the auth service, or any test. The auth filter only ever needs to ask
 * "is this token revoked?"; the auth service only ever needs to call
 * {@code add(jti, exp)} on logout.
 */
public interface TokenDenyList {

    /** Mark a token's {@code jti} as revoked until at least its original {@code expiresAt}. */
    void add(String jti, Instant expiresAt);

    /** @return {@code true} iff the {@code jti} is currently revoked. */
    boolean isRevoked(String jti);

    /** Remove entries whose {@code expiresAt} has passed. Called by a scheduled job. */
    int pruneExpired();
}

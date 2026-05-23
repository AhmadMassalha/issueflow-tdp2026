package com.att.tdp.issueflow.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed binding for {@code jwt.*} properties.
 *
 * <p>Bound from {@code application.yaml}; the {@code secret} must be non-empty
 * at startup (enforced by {@link JwtService}'s constructor — fail-fast keeps a
 * mis-configured production deploy from accepting any token).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long expiresInSeconds, String issuer) {}

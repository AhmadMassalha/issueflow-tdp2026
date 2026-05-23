package com.att.tdp.issueflow.auth.jwt;

import com.att.tdp.issueflow.users.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Signs and parses HS256 JWTs per spec 02 §1.
 *
 * <p>Token shape:
 * <pre>
 * header  = { alg: "HS256", typ: "JWT" }
 * payload = { iss, sub: userId, username, role, jti, iat, exp }
 * </pre>
 *
 * <p>{@link Clock} is injected to make expiry-related tests deterministic
 * without sleeping. Use {@code Clock.systemUTC()} (the bean's default) in
 * production; tests can wire a fixed clock if they need to assert exact
 * {@code exp}/{@code iat} values.
 */
@Service
@Slf4j
public class JwtService {

    /**
     * Sentinel value matching the dev fallback in {@code application.yaml}. When
     * this exact string is seen at startup, we log a loud WARN — same idiom as
     * {@code AdminSeeder} when the default admin/admin credentials are in use.
     */
    private static final String DEV_SECRET_SENTINEL =
            "dev-only-jwt-secret-please-override-via-JWT_SECRET-env-var-in-prod-1234567890";

    private final SecretKey signingKey;
    private final long expiresInSeconds;
    private final String issuer;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable "
                            + "(or jwt.secret in application.yaml for non-prod profiles).");
        }
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 bytes (256 bits) for HS256; got " + keyBytes.length);
        }
        if (DEV_SECRET_SENTINEL.equals(props.secret())) {
            log.warn("================================================================");
            log.warn(" jwt.secret is the dev fallback baked into application.yaml.");
            log.warn(" This is fine for local development and graded review runs.");
            log.warn(" For any non-local deployment, override via the JWT_SECRET env");
            log.warn(" variable with a random 32+ byte secret (e.g.");
            log.warn("   export JWT_SECRET=$(openssl rand -base64 48) ).");
            log.warn("================================================================");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expiresInSeconds = props.expiresInSeconds();
        this.issuer = props.issuer();
        this.clock = clock;
    }

    /** @return a freshly signed access token for {@code user}, with a unique {@code jti}. */
    public IssuedToken generate(User user) {
        Instant now = Instant.now(clock);
        Instant exp = now.plusSeconds(expiresInSeconds);
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, jti, exp, expiresInSeconds);
    }

    /**
     * Verifies signature + expiry and returns the parsed claims.
     *
     * @throws ExpiredJwtException  when the token has expired
     * @throws JwtException         for malformed / wrong-signature / unsupported tokens
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .clock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** @return the {@code jti} claim from {@code token} without throwing on expired tokens. */
    public String getJti(String token) {
        try {
            return parse(token).getId();
        } catch (ExpiredJwtException ex) {
            // We still need the jti of an expired token in some edge cases (cleanup, audit).
            return ex.getClaims().getId();
        }
    }

    /** Lightweight value object returned by {@link #generate(User)}. */
    public record IssuedToken(String token, String jti, Instant expiresAt, long expiresInSeconds) {}
}

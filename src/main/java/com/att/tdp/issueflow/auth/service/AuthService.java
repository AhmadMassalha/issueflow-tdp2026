package com.att.tdp.issueflow.auth.service;

import com.att.tdp.issueflow.auth.api.LoginRequest;
import com.att.tdp.issueflow.auth.api.LoginResponse;
import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.auth.jwt.TokenDenyList;
import com.att.tdp.issueflow.common.exception.DomainException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication operations:
 * <ul>
 *   <li>{@link #login(LoginRequest)} — verifies credentials in constant-ish time
 *       (BCrypt is always invoked, even on user-not-found) and mints a JWT.</li>
 *   <li>{@link #logout(String)} — adds the current token's {@code jti} to the deny-list.</li>
 * </ul>
 *
 * <p>{@code /auth/me} doesn't live in a service — the controller can build the
 * response from the principal already attached to the security context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /**
     * BCrypt of an arbitrary string. The plaintext is unknown to attackers so
     * this never matches anything; its only purpose is to keep the timing of
     * the user-not-found path comparable to a real-user-wrong-password path,
     * mitigating the username-enumeration leak called out in spec 02 §2.
     */
    private static final String SENTINEL_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5b3JaQjpYwQ4kP9o4Y5e6sV3qBmEa";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final TokenDenyList denyList;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        User user = users.findByUsername(req.username()).orElse(null);

        boolean ok;
        if (user == null) {
            // Burn the same time even if the username doesn't exist — see SENTINEL_HASH JavaDoc.
            passwordEncoder.matches(req.password(), SENTINEL_HASH);
            ok = false;
        } else {
            ok = passwordEncoder.matches(req.password(), user.getPasswordHash());
        }

        if (!ok) {
            // Single message + single code regardless of which check failed.
            throw new InvalidCredentialsException();
        }

        JwtService.IssuedToken issued = jwt.generate(user);
        log.debug("Issued JWT for user {} (jti={}, exp={})", user.getId(), issued.jti(), issued.expiresAt());
        return LoginResponse.bearer(issued.token(), issued.expiresInSeconds());
    }

    /**
     * @param rawToken the JWT string from the {@code Authorization} header (without
     *                 the {@code "Bearer "} prefix).
     */
    public void logout(String rawToken) {
        // getJti tolerates already-expired tokens (it's effectively idempotent then).
        String jti = jwt.getJti(rawToken);
        // We don't know the exact expiry, but we can be conservative and keep the entry
        // until *at least* the token's stated exp; the parsed claims tell us.
        java.time.Instant exp;
        try {
            exp = jwt.parse(rawToken).getExpiration().toInstant();
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            exp = ex.getClaims().getExpiration().toInstant();
        } catch (Exception ex) {
            // Token couldn't be parsed at all — this code path is unreachable in
            // practice because the auth filter would have already rejected the
            // request, but keep a sane fallback.
            exp = java.time.Instant.now().plusSeconds(3600);
        }
        denyList.add(jti, exp);
        log.debug("Revoked JWT jti={} (exp={})", jti, exp);
    }

    /** Surfaced as 401 {@code AUTH_INVALID_CREDENTIALS} by the global handler. */
    public static final class InvalidCredentialsException extends DomainException {
        public InvalidCredentialsException() {
            super(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid username or password.");
        }
    }
}

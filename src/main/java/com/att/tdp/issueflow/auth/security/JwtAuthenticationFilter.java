package com.att.tdp.issueflow.auth.security;

import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.auth.jwt.TokenDenyList;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.DomainException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates each request that carries an {@code Authorization: Bearer <jwt>}
 * header. Per spec 02 §3, every endpoint except {@code /auth/login} (and
 * {@code /error}) requires this header.
 *
 * <p>Failure paths translate to domain exceptions and are routed through the
 * {@link HandlerExceptionResolver} so the global {@code @RestControllerAdvice}
 * still maps them to our {@link com.att.tdp.issueflow.common.web.ApiError}
 * envelope (a plain {@code throw} from a filter would bypass the dispatcher).
 *
 * <ul>
 *   <li>Missing / malformed / expired / wrong-signature → {@link ErrorCode#AUTH_TOKEN_INVALID} (401)</li>
 *   <li>Revoked (deny-listed jti) → {@link ErrorCode#AUTH_TOKEN_REVOKED} (401)</li>
 * </ul>
 *
 * <p>This filter does NOT enforce {@code permitAll()} — that's the security
 * config's job. The filter runs for every request; if no header is present,
 * we just don't authenticate and let the chain decide.
 *
 * <p>Registered as an explicit {@code @Bean} in {@code SecurityConfig} (not via
 * {@code @Component}) so {@code @WebMvcTest} slices that don't load
 * {@code SecurityConfig} also don't auto-discover this filter — otherwise
 * Spring would try to wire {@link JwtService} into it and fail, since
 * {@code @WebMvcTest} excludes {@code @Service} beans.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwt;
    private final TokenDenyList denyList;
    private final HandlerExceptionResolver resolver;

    public JwtAuthenticationFilter(JwtService jwt, TokenDenyList denyList, HandlerExceptionResolver resolver) {
        this.jwt = jwt;
        this.denyList = denyList;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            resolver.resolveException(req, res, null,
                    new TokenInvalid("Authorization header missing bearer token."));
            return;
        }

        Claims claims;
        try {
            claims = jwt.parse(token);
        } catch (ExpiredJwtException ex) {
            resolver.resolveException(req, res, null, new TokenInvalid("Token has expired."));
            return;
        } catch (JwtException ex) {
            log.debug("JWT rejected on {}: {}", req.getRequestURI(), ex.getMessage());
            resolver.resolveException(req, res, null, new TokenInvalid("Token is invalid."));
            return;
        }

        if (denyList.isRevoked(claims.getId())) {
            resolver.resolveException(req, res, null, new TokenRevoked("Token has been revoked."));
            return;
        }

        Long userId;
        String username;
        Role role;
        try {
            userId = Long.parseLong(claims.getSubject());
            username = claims.get("username", String.class);
            role = Role.valueOf(claims.get("role", String.class));
        } catch (IllegalArgumentException ex) {
            // Covers NumberFormatException (bad sub) and Role.valueOf failure (bad role claim).
            // Token signature was valid but the payload doesn't match our shape — treat as invalid.
            resolver.resolveException(req, res, null, new TokenInvalid("Token payload is malformed."));
            return;
        }
        IssueFlowUserPrincipal principal = new IssueFlowUserPrincipal(userId, username, null, role);

        AbstractAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        auth.setDetails(claims.getId()); // keep jti accessible for logout without re-parsing
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Re-run on async dispatches (Servlet 3+) — needed by slice 11's
     * {@code StreamingResponseBody} export endpoint and any future
     * async-returning controllers.
     *
     * <p><b>Why this is the right fix:</b> {@link OncePerRequestFilter}'s
     * default ({@code true}) skips the filter on the async re-dispatch.
     * Because we run {@link SecurityContextHolder#clearContext()} in the
     * sync {@code finally} above (we have to — {@code SessionCreationPolicy.STATELESS}
     * means there's no {@code SecurityContextHolderFilter} to restore it
     * later), the async re-dispatch arrives with an empty context, and
     * Spring Security's {@code AuthorizationFilter} denies the request.
     *
     * <p>Returning {@code false} makes the filter re-extract the bearer
     * token from the same request on the async dispatch (the Authorization
     * header still lives on the request) and re-populate
     * {@link SecurityContextHolder}. The {@code try/finally} pattern is
     * idempotent — clearing again at end of the async dispatch is
     * harmless.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** Surfaced as 401 {@code AUTH_TOKEN_INVALID} by the global handler. */
    static final class TokenInvalid extends DomainException {
        TokenInvalid(String message) { super(ErrorCode.AUTH_TOKEN_INVALID, message); }
    }

    /** Surfaced as 401 {@code AUTH_TOKEN_REVOKED} by the global handler. */
    static final class TokenRevoked extends DomainException {
        TokenRevoked(String message) { super(ErrorCode.AUTH_TOKEN_REVOKED, message); }
    }
}

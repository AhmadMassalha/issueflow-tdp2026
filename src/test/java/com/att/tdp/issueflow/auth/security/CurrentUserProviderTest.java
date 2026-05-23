package com.att.tdp.issueflow.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.att.tdp.issueflow.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Branches of {@link CurrentUserProvider#currentUser()} — one test per the
 * three "empty Optional" cases enumerated in the JavaDoc, plus the happy
 * path. Each test cleans up the static {@code SecurityContextHolder} in
 * {@link #clearContext()} so the suite remains order-independent.
 */
class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        // SecurityContextHolder is thread-local and our tests touch it directly;
        // leaving it populated would leak into the next test in the same JVM.
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("returns the principal when the context holds an IssueFlowUserPrincipal")
    void should_returnPrincipal_whenContextHasOurPrincipal() {
        IssueFlowUserPrincipal principal = new IssueFlowUserPrincipal(
                42L, "alice", "hash", Role.ADMIN);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        var actual = provider.currentUser();

        assertThat(actual).contains(principal);
    }

    @Test
    @DisplayName("returns empty when there is no authentication in the context")
    void should_returnEmpty_whenNoAuthentication() {
        // Default fresh context — no setAuthentication call.

        var actual = provider.currentUser();

        assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the authentication is anonymous (e.g. permitAll path)")
    void should_returnEmpty_whenAuthenticationIsAnonymous() {
        // Anonymous tokens carry isAuthenticated()=true (Spring's quirk) but
        // are clearly not "a user did something" — we filter them out by
        // requiring an IssueFlowUserPrincipal specifically.
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        var actual = provider.currentUser();

        assertThat(actual).isEmpty();
    }
}

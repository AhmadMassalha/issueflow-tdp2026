package com.att.tdp.issueflow.auth.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "who is the current user?" within a request.
 *
 * <p>Wraps {@link SecurityContextHolder} so services that previously took no
 * principal argument (UserService, ProjectService, TicketService) can ask
 * for it lazily — and so unit tests can mock the lookup with a single
 * {@code when(provider.currentUser()).thenReturn(Optional.of(...))} call
 * instead of doing static-context mocking.
 *
 * <p>Returns {@link Optional#empty()} in three cases, all of which the
 * callers of {@code log(...)} must handle as "SYSTEM actor":
 * <ol>
 *   <li>No authentication in the context (a background job — slice 13/14).</li>
 *   <li>{@code !authentication.isAuthenticated()} (anonymous filter chain).</li>
 *   <li>Principal is not an {@link IssueFlowUserPrincipal} (defence in depth;
 *       in normal operation our filter only puts our own principal type in
 *       the context, but a future framework upgrade or test injecting a
 *       generic {@code UserDetails} mustn't silently authenticate as user
 *       id 0).</li>
 * </ol>
 *
 * <p>This is the pattern Session 06 D6 calls out: use the provider for
 * "who's auditing this?" lookups; threading the principal as an explicit
 * method param is for services where the principal participates in
 * business logic (e.g., CommentService's RBAC check).
 */
@Component
public class CurrentUserProvider {

    public Optional<IssueFlowUserPrincipal> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof IssueFlowUserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }
}

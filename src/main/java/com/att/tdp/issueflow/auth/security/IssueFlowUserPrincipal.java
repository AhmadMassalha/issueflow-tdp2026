package com.att.tdp.issueflow.auth.security;

import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.users.domain.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Thin {@link UserDetails} carrying just the data the rest of the app needs:
 * the user id (for {@code GET /auth/me}, audit logging, ticket assignment) and
 * the role (for {@code @PreAuthorize("hasRole('ADMIN')")}).
 *
 * <p>{@code passwordHash} is included so {@code DaoAuthenticationProvider} (if
 * we ever wire one up) can call {@code matches}. For the JWT-only path
 * implemented in this slice the field is unused after login completes — the
 * filter authenticates from the token alone.
 *
 * <p>The {@code "ROLE_"} prefix on authorities is what {@code hasRole(...)}
 * expects internally — keeping it here means we don't need a {@code RoleHierarchy}
 * configured.
 */
public record IssueFlowUserPrincipal(
        Long id,
        String username,
        String passwordHash,
        Role role
) implements UserDetails {

    public static IssueFlowUserPrincipal from(User u) {
        return new IssueFlowUserPrincipal(u.getId(), u.getUsername(), u.getPasswordHash(), u.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}

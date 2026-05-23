package com.att.tdp.issueflow.auth.security;

import com.att.tdp.issueflow.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges Spring Security's {@link UserDetailsService} contract to our
 * {@link UserRepository}. Returns {@link IssueFlowUserPrincipal} so the
 * downstream filter can pull {@code id} / {@code role} without re-querying.
 */
@Service
@RequiredArgsConstructor
public class IssueFlowUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(IssueFlowUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));
    }
}

package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password-hashing configuration.
 *
 * <p>Only the {@code spring-security-crypto} module is on the classpath at this
 * slice (per ADR-0004 / Session-02 D2) — no filter chain, no Security auto-config.
 * Slice 3 will introduce {@code spring-boot-starter-security}; the
 * {@link PasswordEncoder} bean defined here remains valid because the starter
 * transitively re-exports the same {@link BCryptPasswordEncoder} class.
 *
 * <p>Cost factor is left at the BCrypt default (10). Increasing it has to be
 * done in lock-step with seed-data password hashes, so we'll revisit if/when
 * profiling shows login latency dominated by hash verification.
 */
@Configuration
public class SecurityCryptoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

package com.att.tdp.issueflow.users.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bindings for {@code app.seed.admin.*}.
 *
 * <p>Drives {@link AdminSeeder}. Disable by setting {@code app.seed.admin.enabled: false}
 * (test profile already does this).
 */
@ConfigurationProperties(prefix = "app.seed.admin")
public record AdminSeedProperties(
        boolean enabled,
        String username,
        String password,
        String email,
        String fullName
) {}

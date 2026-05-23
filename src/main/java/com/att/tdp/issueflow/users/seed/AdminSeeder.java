package com.att.tdp.issueflow.users.seed;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a default ADMIN user on startup if none exists (Session-03 D1).
 *
 * <p>Bypasses the {@code @PreAuthorize("hasRole('ADMIN')")} on
 * {@link com.att.tdp.issueflow.users.service.UserService#create} by writing
 * through the repository directly — there is by definition no ADMIN principal
 * to satisfy the rule at boot time. This is the chicken-and-egg solution
 * justified in {@code docs/decisions/0005-rbac-on-users.md}.
 *
 * <p>Conditional on {@code app.seed.admin.enabled} so it can be disabled in
 * production (or in tests that want to start with an empty user table).
 * Idempotent: if any ADMIN already exists the seeder skips with an INFO log.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed.admin", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AdminSeedProperties props;
    private final AuditLogService auditLog;

    @Override
    @Transactional
    public void run(String... args) {
        if (users.existsByUsername(props.username()) || users.existsByEmail(props.email())) {
            log.info("AdminSeeder: a user with username='{}' or email='{}' already exists; skipping seed.",
                    props.username(), props.email());
            return;
        }

        User admin = new User();
        admin.setUsername(props.username());
        admin.setEmail(props.email());
        admin.setFullName(props.fullName());
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(props.password()));
        User saved = users.save(admin);

        // Session 07 D10: explicit SYSTEM/CREATE/USER row so reviewers can
        // trace "who created this user?" back to the boot-time seeder.
        auditLog.logSystem(AuditAction.CREATE, EntityType.USER, saved.getId(), null);

        log.warn("AdminSeeder: created default ADMIN '{}' with the configured password. "
                        + "ROTATE THIS PASSWORD IMMEDIATELY for any non-dev deployment.",
                props.username());
    }
}

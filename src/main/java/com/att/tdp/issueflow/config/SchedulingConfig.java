package com.att.tdp.issueflow.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods application-wide AND exposes a
 * shared {@link Clock} bean for testable time. Used by:
 * <ul>
 *   <li>The JWT deny-list prune job in {@code InMemoryTokenDenyList}
 *       (slice 3 — first {@code @Scheduled} method in the codebase).</li>
 *   <li>{@link com.att.tdp.issueflow.escalation.service.EscalationScheduler}
 *       (slice 14 — the spec-13 escalation cron). Reuses the existing
 *       {@code @EnableScheduling} (no second config needed) AND the
 *       existing {@link Clock} bean (no second factory needed).</li>
 * </ul>
 *
 * <p><b>Placement rationale (slice 14 D2):</b> kept in a dedicated
 * {@code @Configuration} class — NOT on
 * {@link com.att.tdp.issueflow.IssueFlowApplication} — so test slices
 * can exclude scheduling cleanly. The test profile additionally pushes
 * {@code escalation.fixed-delay-ms} to {@code 999999999} as a belt-
 * and-braces defense (Session 14 D3): the bean wires correctly so we
 * exercise the {@code @Scheduled} parsing path in integration tests,
 * but the cron never actually fires during the ~30s suite.
 *
 * <p><b>Why this {@link Clock} bean instead of {@code Clock.systemUTC()}
 * inline:</b> the JDK's {@code Clock.systemUTC()} is a static factory
 * that can be called from anywhere but can't be overridden in tests.
 * The {@code @Bean} version is injectable AND overridable (via
 * {@code @TestConfiguration} or {@code @MockitoBean}) — standard
 * Spring-Boot idiom for testable time. Slice 14's
 * {@code EscalationServiceTest} uses a {@code @Spy Clock.fixed(...)}
 * locally; {@code EscalationIntegrationTest} overrides this bean
 * application-wide via {@code @Primary}.
 *
 * <p><b>Forward-compat:</b> any future cron / TTL / time-based feature
 * reuses both beans without modifying this class. Validated by slice
 * 14 adding zero new code here.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

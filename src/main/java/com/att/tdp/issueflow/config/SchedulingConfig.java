package com.att.tdp.issueflow.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods application-wide.
 *
 * <p>Initially used by the JWT deny-list prune job ({@code InMemoryTokenDenyList}).
 * Slice 14 will add the ticket-escalation scheduler without needing to re-enable
 * scheduling here.
 *
 * <p>Also provides a single {@link Clock} bean ({@code Clock.systemUTC()}) that
 * any time-sensitive collaborator can inject. Tests can override this with a
 * fixed clock via {@code @MockBean} / {@code @TestConfiguration} without
 * touching the production wiring.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

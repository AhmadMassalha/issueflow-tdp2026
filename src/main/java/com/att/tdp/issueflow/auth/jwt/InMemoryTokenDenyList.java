package com.att.tdp.issueflow.auth.jwt;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Process-local {@link TokenDenyList} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Per ADR 0003 this is sufficient for the assignment and is deliberately
 * lost on restart. Swap with a Redis-backed implementation for horizontal
 * scaling — neither the filter nor the auth service needs to change.
 *
 * <p>The prune job runs every 10 minutes (cheap, bounded by the number of
 * not-yet-expired revoked tokens). It is disabled in test profiles by virtue
 * of {@code @EnableScheduling} living in {@code SchedulingConfig} which the
 * test profile keeps enabled — but the test never advances the clock past the
 * 10-minute mark, so the job effectively never fires during tests.
 */
@Component
@Slf4j
public class InMemoryTokenDenyList implements TokenDenyList {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTokenDenyList(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void add(String jti, Instant expiresAt) {
        revoked.put(jti, expiresAt);
    }

    @Override
    public boolean isRevoked(String jti) {
        Instant exp = revoked.get(jti);
        if (exp == null) {
            return false;
        }
        // Past-expiry entries are no longer "revoked" — the token couldn't be used anyway.
        // We still report true until the prune job removes the entry; this avoids a race
        // where a request slips through between expiry and prune.
        return true;
    }

    @Override
    @Scheduled(fixedDelayString = "PT10M")
    public int pruneExpired() {
        Instant now = Instant.now(clock);
        int before = revoked.size();
        revoked.entrySet().removeIf(e -> !e.getValue().isAfter(now));
        int pruned = before - revoked.size();
        if (pruned > 0) {
            log.debug("Pruned {} expired entries from the token deny-list; {} remain.", pruned, revoked.size());
        }
        return pruned;
    }
}

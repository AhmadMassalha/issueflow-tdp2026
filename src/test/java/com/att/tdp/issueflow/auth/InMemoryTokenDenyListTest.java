package com.att.tdp.issueflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.att.tdp.issueflow.auth.jwt.InMemoryTokenDenyList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryTokenDenyListTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("isRevoked returns true after add; false for unknown jti")
    void should_reportRevocation() {
        InMemoryTokenDenyList list = new InMemoryTokenDenyList(FIXED);
        list.add("jti-1", FIXED.instant().plusSeconds(60));

        assertThat(list.isRevoked("jti-1")).isTrue();
        assertThat(list.isRevoked("unknown")).isFalse();
    }

    @Test
    @DisplayName("pruneExpired removes entries whose expiry has passed")
    void should_pruneExpiredEntries() {
        Clock later = Clock.fixed(Instant.parse("2026-05-23T11:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenDenyList list = new InMemoryTokenDenyList(later);

        list.add("expired", Instant.parse("2026-05-23T10:30:00Z"));
        list.add("still-valid", Instant.parse("2026-05-23T12:00:00Z"));

        int pruned = list.pruneExpired();

        assertThat(pruned).isEqualTo(1);
        assertThat(list.isRevoked("expired")).isFalse();
        assertThat(list.isRevoked("still-valid")).isTrue();
    }

    @Test
    @DisplayName("pruneExpired is a no-op when nothing has expired")
    void should_returnZero_whenNothingToPrune() {
        InMemoryTokenDenyList list = new InMemoryTokenDenyList(FIXED);
        list.add("future", FIXED.instant().plusSeconds(60));

        assertThat(list.pruneExpired()).isZero();
        assertThat(list.isRevoked("future")).isTrue();
    }
}

package com.att.tdp.issueflow.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.att.tdp.issueflow.config.JpaConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies {@code @CreatedDate} and {@code @LastModifiedDate} on {@link BaseEntity}
 * are populated by JPA auditing on insert and bumped on update.
 *
 * <p>Uses a tiny throwaway entity ({@link TestThing}) declared inside this test
 * so the assertion only exercises {@code BaseEntity} behavior. The default
 * {@code @DataJpaTest} entity scan starts at the {@code @SpringBootApplication}
 * package and picks up both this nested entity and real feature entities
 * (e.g. {@code User}); that's fine because we only persist {@code TestThing}.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaConfig.class)
class BaseEntityJpaTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("createdAt and updatedAt are populated on insert")
    void should_populateTimestamps_whenInserted() {
        TestThing thing = new TestThing();
        thing.setLabel("first");

        em.persist(thing);
        em.flush();

        assertThat(thing.getId()).isNotNull();
        assertThat(thing.getCreatedAt()).isNotNull();
        assertThat(thing.getUpdatedAt()).isNotNull();
        // On insert both timestamps come from the same listener tick; allow ±1s slack.
        assertThat(thing.getUpdatedAt()).isCloseTo(thing.getCreatedAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("updatedAt is bumped on update; createdAt is not")
    void should_bumpUpdatedAt_whenUpdated() throws InterruptedException {
        TestThing thing = new TestThing();
        thing.setLabel("first");
        em.persist(thing);
        em.flush();
        Instant createdAt = thing.getCreatedAt();
        Instant initialUpdatedAt = thing.getUpdatedAt();

        Thread.sleep(10); // ensure clock advances at least one tick
        thing.setLabel("second");
        em.flush();

        assertThat(thing.getCreatedAt()).isEqualTo(createdAt);
        assertThat(thing.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    // --------- test fixture entity -----------------------------------------------

    @Entity
    @Table(name = "test_thing")
    public static class TestThing extends BaseEntity {

        @Column(nullable = false)
        private String label;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}

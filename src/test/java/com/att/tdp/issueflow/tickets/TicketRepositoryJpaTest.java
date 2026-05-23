package com.att.tdp.issueflow.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.config.JpaConfig;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Slice-level test for {@link TicketRepository}: confirms persistence, audit
 * timestamps, JPA {@code @Version} increments, the soft-delete column slot,
 * and the project-scoped query.
 *
 * <p>The {@code @Version} round-trip test is the cornerstone proof that ADR
 * 0001's optimistic-locking strategy actually fires at the DB level — without
 * it, the slice-1 mapping in {@code GlobalExceptionHandler.handleOptimisticLock}
 * would be untested at the persistence layer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TicketRepositoryJpaTest {

    @Autowired
    private TicketRepository tickets;

    @PersistenceContext
    private EntityManager em;

    private Ticket newTicket(String title, Long projectId) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setProjectId(projectId);
        return t;
    }

    @BeforeEach
    void wipe() {
        tickets.deleteAll();
        em.flush();
    }

    @Test
    @DisplayName("persists a ticket, populates audit timestamps, @Version starts at 0, isOverdue defaults false, deletedAt null")
    void should_persistAndPopulateDefaults() {
        Ticket saved = tickets.save(newTicket("hello", 1L));
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.isOverdue()).isFalse();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("@Version increments on UPDATE (drives spec §6 / ADR 0001)")
    void should_incrementVersionOnUpdate() {
        Ticket saved = tickets.save(newTicket("hello", 1L));
        em.flush();
        em.clear();
        assertThat(saved.getVersion()).isEqualTo(0L);

        Ticket reloaded = tickets.findById(saved.getId()).orElseThrow();
        reloaded.setTitle("hello v2");
        tickets.save(reloaded);
        em.flush();

        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("stale-version save throws ObjectOptimisticLockingFailureException (handler then maps to TICKET_VERSION_CONFLICT)")
    void should_throwOptimisticLock_whenStaleVersion() {
        // Persist a row, then bump the version once so DB-version > 0.
        Ticket original = tickets.saveAndFlush(newTicket("hello", 1L));
        Long id = original.getId();
        original.setTitle("bumped");
        tickets.saveAndFlush(original);   // DB version: 0 → 1
        em.clear();                       // forget any managed state

        // Construct a "stale" handle as if a second client had loaded version 0
        // and is now trying to save. Hand-build instead of findById-twice, because
        // within a single @DataJpaTest transaction the persistence context returns
        // the SAME managed reference for repeated findById() calls (lesson logged
        // to .cursor/rules/30-testing.mdc).
        Ticket stale = newTicket("hello", 1L);
        stale.setId(id);
        stale.setVersion(0L);             // stale: DB is at version 1
        stale.setTitle("stale write");

        assertThatThrownBy(() -> tickets.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---- Spec 04 §12 ----------------------------------------------------------

    @Test
    @DisplayName("findByProjectId — returns only tickets for the queried project")
    void should_findByProjectId() {
        tickets.save(newTicket("a", 1L));
        tickets.save(newTicket("b", 1L));
        tickets.save(newTicket("c", 2L));
        em.flush();

        List<Ticket> p1 = tickets.findByProjectId(1L);
        List<Ticket> p2 = tickets.findByProjectId(2L);
        List<Ticket> p3 = tickets.findByProjectId(99L);

        assertThat(p1).extracting(Ticket::getTitle).containsExactlyInAnyOrder("a", "b");
        assertThat(p2).extracting(Ticket::getTitle).containsExactly("c");
        assertThat(p3).isEmpty();
    }
}

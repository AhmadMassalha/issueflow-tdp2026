package com.att.tdp.issueflow.dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.config.JpaConfig;
import com.att.tdp.issueflow.dependencies.domain.TicketDependency;
import com.att.tdp.issueflow.dependencies.repository.BlockerSummary;
import com.att.tdp.issueflow.dependencies.repository.TicketDependencyRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
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

/**
 * Persistence + query coverage for {@link TicketDependencyRepository}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>Surrogate id is generated; both FKs round-trip.</li>
 *   <li>{@code (ticket_id, blocker_id)} unique constraint actually fires
 *       at the DB level (Session 08 D1 belt-and-suspenders).</li>
 *   <li>{@code findByTicketIdAndBlockerId} returns the row for the matching
 *       composite, empty for the wrong ticket (the tenancy idiom).</li>
 *   <li>{@code findBlockerSummariesByTicketId} JOINs to {@code Ticket} and
 *       returns id/title/status, sorted by id ascending.</li>
 *   <li>{@code findBlockerIdsByTicketIds} returns the union of blockers for
 *       a frontier of tickets — the BFS batching shape (Session 08 D2).</li>
 *   <li>{@code countOpenBlockers} counts non-DONE blockers; returns 0 when
 *       all blockers are DONE.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TicketDependencyRepositoryJpaTest {

    @Autowired
    private TicketDependencyRepository deps;

    @PersistenceContext
    private EntityManager em;

    private Ticket persistTicket(String title, TicketStatus status, Long projectId) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription("d");
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setProjectId(projectId);
        t.setOverdue(false);
        em.persist(t);
        return t;
    }

    private TicketDependency persistDep(Long ticketId, Long blockerId) {
        TicketDependency d = new TicketDependency();
        d.setTicketId(ticketId);
        d.setBlockerId(blockerId);
        em.persist(d);
        return d;
    }

    @BeforeEach
    void clean() {
        // Slice-7 H2 cross-class Gotcha (see .cursor/rules/30-testing.mdc):
        // AuditIntegrationTest etc. leave rows in the in-mem DB. Reset.
        deps.deleteAll();
        em.flush();
    }

    // ---- persistence + identity --------------------------------------------

    @Test
    @DisplayName("save: surrogate id generated, both FK columns round-trip")
    void should_persistSurrogateId_andColumns() {
        TicketDependency saved = persistDep(1L, 2L);
        em.flush();
        em.clear();

        TicketDependency reloaded = deps.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getTicketId()).isEqualTo(1L);
        assertThat(reloaded.getBlockerId()).isEqualTo(2L);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    // ---- unique constraint --------------------------------------------------

    @Test
    @DisplayName("@UniqueConstraint(ticket_id, blocker_id) rejects duplicate pair at the DB level")
    void should_rejectDuplicatePair_atDbLevel() {
        persistDep(1L, 2L);
        em.flush();

        // Same pair — must blow up at insert time. Note: BaseEntity uses
        // GenerationType.IDENTITY, so em.persist() (not em.flush()) is what
        // actually issues the INSERT — Hibernate needs the auto-generated id
        // back synchronously. The constraint thus fires inside the persist
        // call, NOT inside a subsequent flush. We assert on the message
        // because direct EntityManager calls inside @DataJpaTest's
        // transactional context bypass Spring's exception translation, so
        // the type is Hibernate's native ConstraintViolationException not
        // Spring's DataIntegrityViolationException.
        TicketDependency dup = new TicketDependency();
        dup.setTicketId(1L);
        dup.setBlockerId(2L);

        assertThatThrownBy(() -> em.persist(dup))
                .hasMessageContaining("UK_TICKET_DEPENDENCY_PAIR");
    }

    @Test
    @DisplayName("(1->2) and (2->1) are independent rows — directionality is preserved by the constraint")
    void should_allowReverseDirection_asDifferentRow() {
        persistDep(1L, 2L);
        persistDep(2L, 1L);
        em.flush();

        // No exception — the unique constraint is on the ordered pair.
        assertThat(deps.count()).isEqualTo(2);
    }

    // ---- composite lookup ---------------------------------------------------

    @Test
    @DisplayName("findByTicketIdAndBlockerId: returns the row for the matching composite")
    void should_findByCompositeKey() {
        TicketDependency saved = persistDep(7L, 11L);
        em.flush();
        em.clear();

        assertThat(deps.findByTicketIdAndBlockerId(7L, 11L))
                .isPresent()
                .get()
                .extracting(TicketDependency::getId)
                .isEqualTo(saved.getId());
        // Tenancy idiom: wrong ticket id → empty, NOT cross-leak.
        assertThat(deps.findByTicketIdAndBlockerId(99L, 11L)).isEmpty();
    }

    // ---- projection JOIN ----------------------------------------------------

    @Test
    @DisplayName("findBlockerSummariesByTicketId: JOINs to Ticket and returns id/title/status, asc by id")
    void should_returnBlockerSummaries_orderedAsc() {
        Ticket subject = persistTicket("subject", TicketStatus.TODO, 100L);
        Ticket blockerA = persistTicket("alpha", TicketStatus.IN_PROGRESS, 100L);
        Ticket blockerB = persistTicket("beta", TicketStatus.DONE, 100L);
        em.flush();
        persistDep(subject.getId(), blockerB.getId());
        persistDep(subject.getId(), blockerA.getId());
        em.flush();
        em.clear();

        List<BlockerSummary> blockers = deps.findBlockerSummariesByTicketId(subject.getId());

        assertThat(blockers).hasSize(2);
        assertThat(blockers.get(0).id()).isEqualTo(blockerA.getId()); // asc by id
        assertThat(blockers.get(0).title()).isEqualTo("alpha");
        assertThat(blockers.get(0).status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(blockers.get(1).status()).isEqualTo(TicketStatus.DONE);
    }

    // ---- BFS batching helper ------------------------------------------------

    @Test
    @DisplayName("findBlockerIdsByTicketIds: returns union of blockers for the frontier (BFS shape)")
    void should_returnUnionOfBlockers_forFrontier() {
        // Graph: 1 -> {10, 11}; 2 -> {11, 12}; 3 -> {} (no blockers)
        persistDep(1L, 10L);
        persistDep(1L, 11L);
        persistDep(2L, 11L);
        persistDep(2L, 12L);
        em.flush();

        List<Long> frontier = List.of(1L, 2L, 3L);

        List<Long> next = deps.findBlockerIdsByTicketIds(frontier);

        // 11 appears twice (from 1 and 2) — caller dedupes via the visited set.
        // We don't dedupe at the repository level so a DISTINCT-needing caller
        // can choose.
        assertThat(next).containsExactlyInAnyOrder(10L, 11L, 11L, 12L);
    }

    // ---- countOpenBlockers --------------------------------------------------

    @Test
    @DisplayName("countOpenBlockers: counts blockers whose status is NOT DONE")
    void should_countOnlyOpenBlockers() {
        Ticket subject = persistTicket("subject", TicketStatus.TODO, 100L);
        Ticket blockerOpen = persistTicket("open", TicketStatus.IN_PROGRESS, 100L);
        Ticket blockerClosed = persistTicket("closed", TicketStatus.DONE, 100L);
        em.flush();
        persistDep(subject.getId(), blockerOpen.getId());
        persistDep(subject.getId(), blockerClosed.getId());
        em.flush();

        assertThat(deps.countOpenBlockers(subject.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("countOpenBlockers: returns 0 when all blockers are DONE (transition to DONE is allowed)")
    void should_returnZero_whenAllBlockersDone() {
        Ticket subject = persistTicket("subject", TicketStatus.IN_REVIEW, 100L);
        Ticket b1 = persistTicket("b1", TicketStatus.DONE, 100L);
        Ticket b2 = persistTicket("b2", TicketStatus.DONE, 100L);
        em.flush();
        persistDep(subject.getId(), b1.getId());
        persistDep(subject.getId(), b2.getId());
        em.flush();

        assertThat(deps.countOpenBlockers(subject.getId())).isZero();
    }

    @Test
    @DisplayName("countOpenBlockers: returns 0 when ticket has no blockers at all")
    void should_returnZero_whenNoBlockers() {
        assertThat(deps.countOpenBlockers(999L)).isZero();
    }
}

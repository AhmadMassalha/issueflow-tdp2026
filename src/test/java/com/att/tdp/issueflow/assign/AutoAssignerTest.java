package com.att.tdp.issueflow.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.assign.service.AutoAssigner;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link AutoAssigner}.
 *
 * <p>The actual workload query (with the membership predicate and the
 * tie-break ordering) lives in {@code UserRepository.findWorkloadForProject(...)}
 * — those properties are best-tested with real JPA wiring in
 * {@code AutoAssignIntegrationTest}. This file owns the in-Java logic
 * sitting on top of the query result: pick the head, handle empty,
 * project the candidate id set.
 *
 * <p>For the same reason these tests use stub workload rows in the
 * spec-defined order ({@code count ASC, userId ASC}) — the assigner trusts
 * the SQL ordering and just calls {@code .get(0)}. The integration test
 * verifies the query actually delivers that order.
 */
@ExtendWith(MockitoExtension.class)
class AutoAssignerTest {

    @Mock
    private UserRepository users;

    @InjectMocks
    private AutoAssigner assigner;

    // ---- pickAssignee ------------------------------------------------------

    @Test
    @DisplayName("Spec 12 §Algorithm point 3 — picks the head of the spec-sorted list (lowest count, tie → lowest userId)")
    void pickAssignee_picksHead() {
        when(users.findWorkloadForProject(1L)).thenReturn(List.of(
                new WorkloadEntry(42L, "alice", 0L),
                new WorkloadEntry(7L,  "bob",   2L),
                new WorkloadEntry(99L, "carol", 5L)
        ));

        Optional<Long> picked = assigner.pickAssignee(1L);

        assertThat(picked).contains(42L);
    }

    @Test
    @DisplayName("Spec 12 §Algorithm point 3 — tie on count, lowest userId wins (assumes SQL did the ordering)")
    void pickAssignee_tieBreakByUserId() {
        // The repo always returns in (count ASC, id ASC) order — the assigner
        // trusts this contract and never re-sorts in Java. Stubbing the rows
        // in the SQL-promised order is the test's job; the integration test
        // verifies the SQL keeps that promise.
        when(users.findWorkloadForProject(1L)).thenReturn(List.of(
                new WorkloadEntry(3L,  "alice", 1L),
                new WorkloadEntry(7L,  "bob",   1L),
                new WorkloadEntry(42L, "carol", 1L)
        ));

        assertThat(assigner.pickAssignee(1L)).contains(3L);
    }

    @Test
    @DisplayName("Spec 12 §Algorithm point 4 — no candidates → Optional.empty() (NOT an exception)")
    void pickAssignee_emptyWhenNoCandidates() {
        when(users.findWorkloadForProject(1L)).thenReturn(List.of());

        Optional<Long> picked = assigner.pickAssignee(1L);

        assertThat(picked).isEmpty();
    }

    @Test
    @DisplayName("pickAssignee — single candidate is trivially picked")
    void pickAssignee_singleCandidate() {
        when(users.findWorkloadForProject(2L)).thenReturn(List.of(
                new WorkloadEntry(99L, "solo", 17L)
        ));

        assertThat(assigner.pickAssignee(2L)).contains(99L);
    }

    // ---- candidateIdsFor ---------------------------------------------------

    @Test
    @DisplayName("Session 13 D7 — candidateIdsFor returns the set of member user ids (used by TicketService for membership validation)")
    void candidateIdsFor_returnsAllMemberIds() {
        when(users.findWorkloadForProject(1L)).thenReturn(List.of(
                new WorkloadEntry(7L,  "alice", 0L),
                new WorkloadEntry(42L, "bob",   3L),
                new WorkloadEntry(99L, "carol", 5L)
        ));

        assertThat(assigner.candidateIdsFor(1L)).containsExactlyInAnyOrder(7L, 42L, 99L);
    }

    @Test
    @DisplayName("Session 13 D7 — candidateIdsFor returns empty set when no members (vs. throwing) so caller can decide")
    void candidateIdsFor_emptyWhenNoMembers() {
        when(users.findWorkloadForProject(1L)).thenReturn(List.of());

        assertThat(assigner.candidateIdsFor(1L)).isEmpty();
    }
}

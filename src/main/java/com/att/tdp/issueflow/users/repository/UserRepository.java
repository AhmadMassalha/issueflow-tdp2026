package com.att.tdp.issueflow.users.repository;

import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.users.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link User}.
 *
 * <p>Existence checks ({@code existsByUsername}, {@code existsByEmail}) are used
 * by {@code UserService} to pre-empt unique-constraint violations and return the
 * spec'd {@code USER_DUPLICATE_*} codes instead of the generic
 * {@code DATA_INTEGRITY_VIOLATION}.
 *
 * <p>{@link #findMentionedUsersByLoweredUsernames(Collection)} is the
 * mention extractor's lookup (spec 09 + Session 10 D2) — case-insensitive
 * username resolution in one batched query. Lives here because it returns
 * the {@code User}-shaped projection.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Case-insensitive batch lookup used by the mention extractor.
     * Callers must lowercase their input first (the service does the
     * lowercasing once, so we don't pay {@code lower(?)} per element in
     * the IN list). Unknown handles produce no row (silently ignored per
     * spec 09 §Extraction).
     *
     * <p>Returns the projection directly so the service can hand it
     * straight to {@code CommentResponse.from(comment, summaries)}
     * without an intermediate {@code User} round-trip.
     */
    @Query("""
           select new com.att.tdp.issueflow.mentions.api.MentionedUserSummary(
               u.id, u.username, u.fullName)
           from User u
           where lower(u.username) in :loweredUsernames
           """)
    List<MentionedUserSummary> findMentionedUsersByLoweredUsernames(
            @Param("loweredUsernames") Collection<String> loweredUsernames);

    /**
     * Slice 13 — workload projection for the auto-assigner AND the
     * {@code GET /projects/{projectId}/workload} endpoint (spec 12 §Endpoint
     * + §Algorithm). Single JPQL trip — Session 13 D4.
     *
     * <p><b>Candidate set</b> (ADR 0007 — project membership):
     * <ul>
     *   <li>{@code role = DEVELOPER} (spec 12 §1 + §2 — excludes ADMINs).</li>
     *   <li>AND ( is the project owner OR has at least one ticket in this
     *       project ). The owner clause is the bootstrap-case carve-out: a
     *       brand-new project with zero tickets still has its owner as a
     *       candidate (if the owner is a DEVELOPER).</li>
     * </ul>
     *
     * <p><b>Count</b> is the number of NON-DONE tickets currently assigned to
     * the user in this project. {@code @SQLRestriction} on {@code Ticket}
     * (slice 9) transparently excludes soft-deleted rows. {@code LEFT JOIN}
     * so a candidate with zero open tickets reports
     * {@code openTicketCount = 0} instead of being absent from the result.
     *
     * <p><b>Sort</b> {@code count ASC, u.id ASC} (spec 12 §Algorithm point 3:
     * "tie-break: lowest user.id"). The auto-assigner picks
     * {@code result.get(0)} for that exact reason — picking the head of a
     * spec-sorted list is the cleanest "lowest count, tied → lowest id"
     * implementation.
     *
     * <p><b>Why JPQL not native:</b> JPQL respects the {@code @SQLRestriction}
     * on {@code Ticket} (native queries don't), so soft-deleted tickets
     * drop out of BOTH the membership EXISTS predicate AND the LEFT JOIN
     * count without any explicit {@code deleted_at IS NULL} clause. Less
     * code, less drift risk.
     */
    @Query("""
           select new com.att.tdp.issueflow.assign.api.WorkloadEntry(
               u.id, u.username, count(t.id))
           from User u
              left join Ticket t
                  on t.assigneeId = u.id
                  and t.projectId = :projectId
                  and t.status <> com.att.tdp.issueflow.common.enums.TicketStatus.DONE
           where u.role = com.att.tdp.issueflow.common.enums.Role.DEVELOPER
             and (
                  u.id = (select p.ownerId from Project p where p.id = :projectId)
                  or exists (
                      select 1 from Ticket m
                      where m.projectId = :projectId
                        and m.assigneeId = u.id
                  )
             )
           group by u.id, u.username
           order by count(t.id) asc, u.id asc
           """)
    List<WorkloadEntry> findWorkloadForProject(@Param("projectId") Long projectId);
}

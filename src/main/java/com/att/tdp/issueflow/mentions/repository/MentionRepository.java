package com.att.tdp.issueflow.mentions.repository;

import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.domain.Mention;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link Mention}.
 *
 * <p>Five query patterns:
 *
 * <ul>
 *   <li>{@link #findMentionedUserIdsByCommentId(Long)} — list raw user-id
 *       set for a comment. Backs the "old set" half of the diff sync
 *       (Session 10 D3) on comment update.</li>
 *   <li>{@link #findMentionedUsersByCommentId(Long)} — same fetch, but as
 *       the rich {@link MentionedUserSummary} projection. Used by
 *       {@code CommentResponse.from(...)} on the create/update/read
 *       paths so the response carries the spec §1 shape in one query
 *       per comment.</li>
 *   <li>{@link #findCommentMentionsByCommentIds(Collection)} — batched
 *       flat projection for the {@code /users/{id}/mentions} listing.
 *       Returns one {@link CommentMentionRow} per (comment, mentioned-user)
 *       pair; the service groups by {@code commentId} client-side.
 *       Single round-trip for a whole page of comments — avoids the
 *       N+1 footgun.</li>
 *   <li>{@link #deleteByCommentId(Long)} — cleanup on comment hard-delete
 *       (Session 10 D5) and the "DELETE half" of the update sync diff.</li>
 *   <li>{@link #findCommentIdsForMentionedUser(Long, Pageable)} — backs
 *       {@code GET /users/{id}/mentions} (spec 09 §3, newest first by
 *       {@code mention.created_at}). Returns just comment ids so the
 *       service can hydrate them via {@code CommentRepository} in a
 *       narrow follow-up fetch — two narrow queries beat one wide JOIN
 *       whose count query Spring would generate as a JOINed COUNT.</li>
 * </ul>
 */
public interface MentionRepository extends JpaRepository<Mention, Long> {

    @Query("select m.mentionedUserId from Mention m where m.commentId = :commentId")
    List<Long> findMentionedUserIdsByCommentId(@Param("commentId") Long commentId);

    @Query("""
           select new com.att.tdp.issueflow.mentions.api.MentionedUserSummary(
               u.id, u.username, u.fullName)
           from Mention m
             join com.att.tdp.issueflow.users.domain.User u on u.id = m.mentionedUserId
           where m.commentId = :commentId
           order by u.id asc
           """)
    List<MentionedUserSummary> findMentionedUsersByCommentId(@Param("commentId") Long commentId);

    /**
     * Batched flat projection of (commentId, mentionedUser*) for a set of
     * comments. Returns one row per (comment, mentioned-user) pair —
     * callers must group by {@code commentId()} client-side.
     *
     * <p>Used by {@code MentionService.findForUser} so a paginated list
     * of comments-that-mention-me lights up all its {@code mentionedUsers}
     * in a single round-trip, regardless of page size. Avoiding N+1 here
     * is the only reason this method exists (the single-comment helper
     * above is sufficient for create/update response assembly).
     *
     * <p>If {@code commentIds} is empty the service must short-circuit
     * — passing an empty collection to {@code IN ()} is JPA-driver-
     * dependent and we don't want to find out which driver handles it
     * which way.
     */
    @Query("""
           select new com.att.tdp.issueflow.mentions.repository.MentionRepository$CommentMentionRow(
               m.commentId, u.id, u.username, u.fullName)
           from Mention m
             join com.att.tdp.issueflow.users.domain.User u on u.id = m.mentionedUserId
           where m.commentId in :commentIds
           order by m.commentId asc, u.id asc
           """)
    List<CommentMentionRow> findCommentMentionsByCommentIds(
            @Param("commentIds") Collection<Long> commentIds);

    @Modifying
    @Query("delete from Mention m where m.commentId = :commentId")
    int deleteByCommentId(@Param("commentId") Long commentId);

    /**
     * Bulk DELETE for the "remove" half of the sync diff (Session 10 D3).
     * Single SQL statement per call rather than per-row delete — keeps the
     * comment-update transaction tight even when a user pastes a block
     * that drops a dozen mentions.
     */
    @Modifying
    @Query("""
           delete from Mention m
           where m.commentId = :commentId
             and m.mentionedUserId in :mentionedUserIds
           """)
    int deleteByCommentIdAndMentionedUserIds(
            @Param("commentId") Long commentId,
            @Param("mentionedUserIds") Collection<Long> mentionedUserIds);

    /**
     * Page of comment ids that mention {@code userId}, ordered by mention
     * creation time descending (spec 09 §3 — newest first). The
     * {@link Pageable} carries offset/limit; Spring synthesizes the
     * matching {@code count(*)} query for the envelope's {@code total}
     * field. Tiebreaker on {@code id desc} keeps the order deterministic
     * when two mentions land in the same {@code @CreationTimestamp} clock
     * tick (Hibernate stamps at flush time — happens within tests, less
     * common in prod but still possible under load).
     */
    @Query("""
           select m.commentId
           from Mention m
           where m.mentionedUserId = :userId
           order by m.createdAt desc, m.id desc
           """)
    Page<Long> findCommentIdsForMentionedUser(
            @Param("userId") Long userId, Pageable pageable);

    /**
     * Flat row used by {@link #findCommentMentionsByCommentIds(Collection)}.
     * A constructor-expression target — Hibernate needs the
     * {@code public} record constructor to match the JPQL
     * {@code select new …(…)} field order exactly.
     */
    record CommentMentionRow(
            Long commentId,
            Long userId,
            String username,
            String fullName) {

        public MentionedUserSummary toUser() {
            return new MentionedUserSummary(userId, username, fullName);
        }
    }
}

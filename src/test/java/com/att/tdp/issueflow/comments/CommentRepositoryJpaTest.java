package com.att.tdp.issueflow.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Slice-level test for {@link CommentRepository}: confirms persistence, audit
 * timestamps, JPA {@code @Version} increments, the {@code newest-first}
 * derived query (spec 05 §2), and the {@code (id, ticketId)} tenancy query
 * (Session 06 D2).
 *
 * <p>The stale-version test uses the same "hand-build a detached entity"
 * technique as {@code TicketRepositoryJpaTest} per the
 * {@code .cursor/rules/30-testing.mdc} Gotcha — within a single
 * {@code @DataJpaTest} transaction, repeated {@code findById} calls return
 * the same managed reference, so simulating concurrent edits requires
 * bypassing the first-level cache.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class CommentRepositoryJpaTest {

    @Autowired
    private CommentRepository comments;

    @PersistenceContext
    private EntityManager em;

    private Comment newComment(Long ticketId, Long authorId, String content) {
        Comment c = new Comment();
        c.setTicketId(ticketId);
        c.setAuthorId(authorId);
        c.setContent(content);
        return c;
    }

    @BeforeEach
    void wipe() {
        comments.deleteAll();
        em.flush();
    }

    @Test
    @DisplayName("persists a comment, populates audit timestamps, @Version starts at 0")
    void should_persistAndPopulateDefaults() {
        Comment saved = comments.save(newComment(1L, 10L, "hi"));
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getContent()).isEqualTo("hi");
    }

    @Test
    @DisplayName("@Version increments on UPDATE (spec 05 §3 / ADR 0001)")
    void should_incrementVersionOnUpdate() {
        Comment saved = comments.save(newComment(1L, 10L, "first"));
        em.flush();
        em.clear();
        assertThat(saved.getVersion()).isEqualTo(0L);

        Comment reloaded = comments.findById(saved.getId()).orElseThrow();
        reloaded.setContent("first (edited)");
        comments.save(reloaded);
        em.flush();

        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("stale-version save throws ObjectOptimisticLockingFailureException (handler then maps to COMMENT_VERSION_CONFLICT)")
    void should_throwOptimisticLock_whenStaleVersion() {
        // Persist + bump once so DB version > 0.
        Comment original = comments.saveAndFlush(newComment(1L, 10L, "v0"));
        Long id = original.getId();
        original.setContent("v1");
        comments.saveAndFlush(original);  // DB version: 0 → 1
        em.clear();

        // Hand-build a detached stale handle (see .cursor/rules/30-testing.mdc Gotcha).
        Comment stale = newComment(1L, 10L, "stale write");
        stale.setId(id);
        stale.setVersion(0L);             // stale: DB is at version 1

        assertThatThrownBy(() -> comments.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---- Spec 05 §2 — list, newest first --------------------------------------

    @Test
    @DisplayName("findByTicketIdOrderByCreatedAtDesc — returns only the queried ticket's comments, newest first")
    void should_findByTicketIdOrderedNewestFirst() throws InterruptedException {
        // Sleep between inserts so createdAt is strictly increasing — H2's
        // timestamp resolution is microseconds on macOS, but back-to-back
        // saves can still tie. 10ms is fine and keeps the test fast.
        Comment c1 = comments.save(newComment(1L, 10L, "first"));
        em.flush();
        Thread.sleep(Duration.ofMillis(10));
        Comment c2 = comments.save(newComment(1L, 10L, "second"));
        em.flush();
        Thread.sleep(Duration.ofMillis(10));
        Comment c3 = comments.save(newComment(1L, 10L, "third"));
        em.flush();
        // Different-ticket noise that must NOT appear in the result:
        comments.save(newComment(2L, 10L, "other ticket"));
        em.flush();

        List<Comment> result = comments.findByTicketIdOrderByCreatedAtDesc(1L);

        assertThat(result).extracting(Comment::getId)
                .containsExactly(c3.getId(), c2.getId(), c1.getId());
        // Belt-and-suspenders: confirm strict descending order on the actual
        // timestamps too, in case future H2 versions or clock skews loosen
        // the insertion-order proxy.
        for (int i = 0; i < result.size() - 1; i++) {
            Instant earlier = result.get(i + 1).getCreatedAt();
            Instant later = result.get(i).getCreatedAt();
            assertThat(later).isAfterOrEqualTo(earlier);
        }
    }

    @Test
    @DisplayName("findByTicketIdOrderByCreatedAtDesc — empty list for a ticket with no comments")
    void should_returnEmpty_whenNoComments() {
        comments.save(newComment(1L, 10L, "only here on ticket 1"));
        em.flush();

        assertThat(comments.findByTicketIdOrderByCreatedAtDesc(99L)).isEmpty();
    }

    // ---- Session 06 D2 — URL tenancy ------------------------------------------

    @Test
    @DisplayName("findByIdAndTicketId — returns the comment when both ids match")
    void should_findByIdAndTicketId_whenMatch() {
        Comment saved = comments.save(newComment(1L, 10L, "hello"));
        em.flush();

        Optional<Comment> found = comments.findByIdAndTicketId(saved.getId(), 1L);

        assertThat(found).hasValueSatisfying(c -> {
            assertThat(c.getId()).isEqualTo(saved.getId());
            assertThat(c.getTicketId()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("findByIdAndTicketId — empty when ticketId does not match (Session 06 D2)")
    void should_returnEmpty_whenTicketIdMismatch() {
        Comment saved = comments.save(newComment(1L, 10L, "hello"));
        em.flush();

        // Same comment id, wrong ticket id → empty. The service then raises
        // 404 COMMENT_NOT_FOUND with the same envelope as "comment missing"
        // so the wrong-ticket case cannot be used as an existence oracle.
        assertThat(comments.findByIdAndTicketId(saved.getId(), 2L)).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndTicketId — empty when commentId does not exist at all")
    void should_returnEmpty_whenCommentMissing() {
        assertThat(comments.findByIdAndTicketId(999_999L, 1L)).isEmpty();
    }
}

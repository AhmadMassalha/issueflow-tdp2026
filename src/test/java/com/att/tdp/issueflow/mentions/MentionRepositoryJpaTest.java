package com.att.tdp.issueflow.mentions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.config.JpaConfig;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.domain.Mention;
import com.att.tdp.issueflow.mentions.repository.MentionRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.users.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Persistence + query coverage for {@link MentionRepository}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>Surrogate id is generated; both FKs round-trip; {@code createdAt}
 *       auto-populates via {@code @CreationTimestamp}.</li>
 *   <li>{@code (comment_id, mentioned_user_id)} unique constraint fires
 *       at the DB level — protects against the "same user mentioned twice
 *       in one comment" duplicate (Session 10 D2 application-side dedup
 *       is belt; this is suspenders).</li>
 *   <li>{@code findMentionedUserIdsByCommentId} + {@code findMentionedUsersByCommentId}
 *       — the diff-sync's read side (raw ids) AND the response projection
 *       (joined user summary).</li>
 *   <li>{@code findCommentMentionsByCommentIds} — the BATCHED projection
 *       used by the {@code /users/{id}/mentions} listing; single query
 *       returns all (commentId, user) rows for a page.</li>
 *   <li>{@code deleteByCommentId} + {@code deleteByCommentIdAndMentionedUserIds}
 *       — cleanup on comment delete and the surgical DELETE half of the
 *       diff sync.</li>
 *   <li>{@code findCommentIdsForMentionedUser} paged + sorted newest first
 *       by {@code createdAt} (spec 09 §3).</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class MentionRepositoryJpaTest {

    @Autowired
    private MentionRepository mentions;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void clean() {
        // Same H2 cross-class hygiene as TicketDependencyRepositoryJpaTest
        // (slice 7 Gotcha) — purge any rows other @SpringBootTest classes
        // may have leaked into this JVM's in-memory DB.
        mentions.deleteAll();
        em.flush();
        em.clear();
    }

    // ---- fixtures -----------------------------------------------------------

    /**
     * Per-test counter for username uniqueness. Slice 10 Gotcha (see
     * {@code .cursor/rules/30-testing.mdc}): {@code MentionsIntegrationTest}
     * (a {@code @SpringBootTest}) commits user rows like "alice"/"bob" into
     * the shared H2 in-mem DB; running this {@code @DataJpaTest} after it
     * would otherwise hit {@code UK_USERS_USERNAME} when we seed our own
     * fixtures. Prefixing every username with a test-scoped counter (which
     * resets per-test since the field is instance-scoped, but is unique
     * within a single test method's seeded users) sidesteps the collision
     * without needing to wipe the users table (which would interfere with
     * the {@code @DataJpaTest} rollback semantics — and would also wipe
     * AdminSeeder's rows for any test that depends on them).
     */
    private int userCounter = 0;

    private User persistUser(String label) {
        // Namespace: "mrjt-{counter}-{label}" — "mrjt" = MentionRepoJpaTest.
        String username = "mrjt-" + (++userCounter) + "-" + label;
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName("Full " + label);
        u.setRole(Role.DEVELOPER);
        u.setPasswordHash("x");
        em.persist(u);
        return u;
    }

    private Ticket persistTicket() {
        Ticket t = new Ticket();
        t.setTitle("t");
        t.setDescription("d");
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setProjectId(1L); // tenancy not checked at this layer
        t.setOverdue(false);
        em.persist(t);
        return t;
    }

    private Comment persistComment(Long ticketId, Long authorId, String content) {
        Comment c = new Comment();
        c.setTicketId(ticketId);
        c.setAuthorId(authorId);
        c.setContent(content);
        em.persist(c);
        return c;
    }

    private Mention persistMention(Long commentId, Long userId) {
        Mention m = new Mention(commentId, userId);
        em.persist(m);
        return m;
    }

    // ---- 1. persistence + auto timestamp -----------------------------------

    @Test
    @DisplayName("persist: id is generated; createdAt populates via @CreationTimestamp")
    void should_persistMention_andStampCreatedAt() {
        User u = persistUser("alice");
        Ticket t = persistTicket();
        Comment c = persistComment(t.getId(), u.getId(), "hi");

        Mention saved = persistMention(c.getId(), u.getId());
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull().isBefore(Instant.now().plusSeconds(60));
    }

    // ---- 2. unique constraint ----------------------------------------------

    @Test
    @DisplayName("DB-level UK_MENTION_COMMENT_USER rejects (comment_id, mentioned_user_id) duplicate")
    void should_rejectDuplicatePair_atDbLevel() {
        User u = persistUser("alice");
        Ticket t = persistTicket();
        Comment c = persistComment(t.getId(), u.getId(), "hi");

        persistMention(c.getId(), u.getId());
        em.flush();

        // Same comment + same user — must violate the unique constraint.
        // Same idiom as TicketDependencyRepositoryJpaTest's
        // "rejectDuplicatePair" (slice 8 Gotcha): IDENTITY-generated id
        // causes the INSERT to fire at persist() time, so the exception
        // arrives there rather than at the next flush().
        Mention dup = new Mention(c.getId(), u.getId());
        assertThatThrownBy(() -> {
            em.persist(dup);
            em.flush();
        }).hasMessageContaining("UK_MENTION_COMMENT_USER");
    }

    // ---- 3. read paths (single comment) ------------------------------------

    @Test
    @DisplayName("findMentionedUserIdsByCommentId returns raw set; findMentionedUsersByCommentId joins to User for the projection")
    void should_returnRawIdsAndProjections_forSingleComment() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");
        User carol = persistUser("carol");
        Ticket t = persistTicket();
        Comment c1 = persistComment(t.getId(), alice.getId(), "hi @alice @bob");
        Comment c2 = persistComment(t.getId(), alice.getId(), "hi @carol");

        persistMention(c1.getId(), alice.getId());
        persistMention(c1.getId(), bob.getId());
        persistMention(c2.getId(), carol.getId());
        em.flush();
        em.clear();

        assertThat(mentions.findMentionedUserIdsByCommentId(c1.getId()))
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());

        List<MentionedUserSummary> projected = mentions.findMentionedUsersByCommentId(c1.getId());
        assertThat(projected).hasSize(2);
        // Sort is "u.id asc" in the JPQL — assertions reflect that.
        // Username has the test-scoped namespace prefix (see persistUser
        // JavaDoc) so we check by ID + fullName which encode the label.
        assertThat(projected.get(0).id()).isEqualTo(alice.getId());
        assertThat(projected.get(0).fullName()).isEqualTo("Full alice");
        assertThat(projected.get(1).id()).isEqualTo(bob.getId());
        assertThat(projected.get(1).fullName()).isEqualTo("Full bob");
    }

    // ---- 4. batched projection ---------------------------------------------

    @Test
    @DisplayName("findCommentMentionsByCommentIds returns flat (commentId, user) rows for a page — single query")
    void should_returnFlatRows_forBatchOfCommentIds() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");
        Ticket t = persistTicket();
        Comment c1 = persistComment(t.getId(), alice.getId(), "hi @alice");
        Comment c2 = persistComment(t.getId(), alice.getId(), "hi @alice @bob");
        Comment c3 = persistComment(t.getId(), alice.getId(), "no mentions here");

        persistMention(c1.getId(), alice.getId());
        persistMention(c2.getId(), alice.getId());
        persistMention(c2.getId(), bob.getId());
        em.flush();
        em.clear();

        var rows = mentions.findCommentMentionsByCommentIds(
                List.of(c1.getId(), c2.getId(), c3.getId()));

        // c1 has 1 mention, c2 has 2, c3 has 0 → 3 rows total.
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.commentId())
                .containsExactlyInAnyOrder(c1.getId(), c2.getId(), c2.getId());
        // toUser() conversion: round-trip the projection.
        // Compare against ids (usernames are namespaced — see persistUser).
        assertThat(rows.stream().filter(r -> r.commentId().equals(c2.getId())).map(r -> r.toUser().id()))
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    // ---- 5. deletes --------------------------------------------------------

    @Test
    @DisplayName("deleteByCommentId removes all mentions for a single comment (idempotent if none)")
    void should_deleteAllMentions_forComment() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");
        Ticket t = persistTicket();
        Comment c = persistComment(t.getId(), alice.getId(), "hi @alice @bob");
        persistMention(c.getId(), alice.getId());
        persistMention(c.getId(), bob.getId());
        em.flush();

        int removed = mentions.deleteByCommentId(c.getId());
        em.flush();
        em.clear();

        assertThat(removed).isEqualTo(2);
        assertThat(mentions.findMentionedUserIdsByCommentId(c.getId())).isEmpty();

        // Idempotency: second call on the same comment returns 0, not an error.
        assertThat(mentions.deleteByCommentId(c.getId())).isZero();
    }

    @Test
    @DisplayName("deleteByCommentIdAndMentionedUserIds removes only the surgical subset (sync's DELETE half)")
    void should_deleteOnlyTheTargetedUsers() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");
        User carol = persistUser("carol");
        Ticket t = persistTicket();
        Comment c = persistComment(t.getId(), alice.getId(), "hi @alice @bob @carol");
        persistMention(c.getId(), alice.getId());
        persistMention(c.getId(), bob.getId());
        persistMention(c.getId(), carol.getId());
        em.flush();

        int removed = mentions.deleteByCommentIdAndMentionedUserIds(
                c.getId(), List.of(alice.getId(), carol.getId()));
        em.flush();
        em.clear();

        assertThat(removed).isEqualTo(2);
        // Bob survives.
        assertThat(mentions.findMentionedUserIdsByCommentId(c.getId()))
                .containsExactly(bob.getId());
    }

    // ---- 6. paged sort -----------------------------------------------------

    @Test
    @DisplayName("Spec 09 §3 + Session 10 D11: findCommentIdsForMentionedUser returns page newest first by createdAt")
    void should_returnPagedCommentIds_newestFirst() {
        User alice = persistUser("alice");
        User bob = persistUser("bob");
        Ticket t = persistTicket();

        // Three comments, each mentioning alice. We sleep 5ms between each
        // to guarantee distinct @CreationTimestamp values — H2 clock
        // resolution is ms, so the sleep is sufficient (and tiny enough
        // not to make the test feel slow).
        Comment c1 = persistComment(t.getId(), bob.getId(), "first @alice");
        persistMention(c1.getId(), alice.getId());
        em.flush();
        sleepMs(5);
        Comment c2 = persistComment(t.getId(), bob.getId(), "second @alice");
        persistMention(c2.getId(), alice.getId());
        em.flush();
        sleepMs(5);
        Comment c3 = persistComment(t.getId(), bob.getId(), "third @alice");
        persistMention(c3.getId(), alice.getId());
        em.flush();
        em.clear();

        Page<Long> page = mentions.findCommentIdsForMentionedUser(
                alice.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        // Newest first → c3, c2, c1.
        assertThat(page.getContent()).containsExactly(c3.getId(), c2.getId(), c1.getId());
    }

    @Test
    @DisplayName("findCommentIdsForMentionedUser: page out of range returns empty content with correct total (spec 09 §5)")
    void should_returnEmpty_withCorrectTotal_whenPageOutOfRange() {
        User alice = persistUser("alice");
        Ticket t = persistTicket();
        Comment c = persistComment(t.getId(), alice.getId(), "@alice");
        persistMention(c.getId(), alice.getId());
        em.flush();
        em.clear();

        Page<Long> page = mentions.findCommentIdsForMentionedUser(
                alice.getId(), PageRequest.of(5, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

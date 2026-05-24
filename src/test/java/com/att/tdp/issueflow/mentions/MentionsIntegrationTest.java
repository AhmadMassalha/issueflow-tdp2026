package com.att.tdp.issueflow.mentions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.mentions.repository.MentionRepository;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end proof for the slice-10 properties unit tests can't fully
 * verify on their own:
 *
 * <ol>
 *   <li><b>Real-DB sync on comment create</b> — POST /comments with
 *       {@code "hi @alice"} writes a {@code Mention} row in the same
 *       transaction; GET /users/{alice-id}/mentions immediately returns
 *       the comment.</li>
 *   <li><b>Update diff with unchanged + added mentions</b> — editing a
 *       comment to add {@code @bob} preserves alice's row (and its
 *       {@code created_at}) AND inserts a new row for bob.</li>
 *   <li><b>Update diff with removed mention</b> — editing to drop
 *       alice removes her row; she stops seeing the comment in her
 *       {@code /mentions} listing.</li>
 *   <li><b>Hard delete cascade</b> — deleting the comment removes the
 *       mention rows; the listing shrinks accordingly.</li>
 *   <li><b>Pagination envelope</b> — {@code /mentions} responds with
 *       the {@code {data, total, page, pageSize}} envelope and
 *       newest-first sort end-to-end (spec 09 §3 + §4).</li>
 *   <li><b>Audit (Session 10 D10)</b> — comment CREATE/UPDATE/DELETE
 *       audit rows land; no MENTION.* audit rows are emitted (mentions
 *       are derived data; the comment row is the system-of-record event).</li>
 *   <li><b>Spec 09 §6 self-mention</b> — mentioning yourself works
 *       end-to-end.</li>
 * </ol>
 *
 * <p>Isolation idiom: same {@code wipe()} pattern as
 * {@code DependencyIntegrationTest} (slice 8) — drop everything in
 * dependency order before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MentionsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private CommentRepository comments;

    @Autowired
    private MentionRepository mentions;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User author;
    private User alice;
    private User bob;
    private Project project;
    private Ticket ticket;

    @BeforeEach
    void wipe() {
        mentions.deleteAll();
        comments.deleteAll();
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();

        // Username chosen for the slice-10 regex `[A-Za-z0-9_]{3,32}` —
        // hyphens are NOT in the character class, so a username like
        // "mt-author" would mean "@mt-author" extracts only "mt" (2 chars,
        // sub-minimum) and never resolves. Underscore is allowed, so we
        // can keep the test prefix.
        author = persistUser("mt_author", "mt-author@example.com", Role.DEVELOPER, "pw");
        alice = persistUser("alice", "alice@example.com", Role.DEVELOPER, "pw");
        bob = persistUser("bob", "bob@example.com", Role.DEVELOPER, "pw");
        project = persistProject("mt-project", author.getId());
        ticket = persistTicket(project.getId());

        // Drop everything the seed paths might have written.
        auditLogs.deleteAll();
    }

    // ---- 1. Create comment → mention row → listing ---------------------------

    @Test
    @DisplayName("End-to-end: POST comment with @alice writes a Mention row and surfaces on /users/{alice}/mentions")
    void create_persistsMention_andListing() throws Exception {
        String token = login("mt_author", "pw");
        // Author posts a comment mentioning alice.
        long commentId = createComment(token, "hello @alice, please review");

        // Mention table now has exactly one row pointing at alice.
        assertThat(mentions.findMentionedUserIdsByCommentId(commentId))
                .containsExactly(alice.getId());

        // GET /users/{alice}/mentions returns the comment with the full
        // mentionedUsers shape (D7).
        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.data[0].id").value((int) commentId))
                .andExpect(jsonPath("$.data[0].content").value("hello @alice, please review"))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].id").value(alice.getId().intValue()))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].fullName").value("alice"));
    }

    // ---- 2. Update preserves alice, adds bob --------------------------------

    @Test
    @DisplayName("End-to-end: PATCH comment from @alice to @alice + @bob preserves alice's existing Mention row and inserts a fresh one for bob (Session 10 D3 diff)")
    void update_preservesExistingMentions_andAddsNew() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "@alice please review");
        Long aliceMentionIdBefore = mentions.findAll().stream()
                .filter(m -> m.getCommentId().equals(commentId)
                        && m.getMentionedUserId().equals(alice.getId()))
                .findFirst().orElseThrow().getId();

        // Now edit to also mention bob.
        mvc.perform(patch("/tickets/{tid}/comments/{cid}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"@alice and @bob please review\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers.length()").value(2));

        // Alice's row UNCHANGED (same id == surgical diff didn't churn it);
        // bob's row added.
        Long aliceRowAfter = mentions.findAll().stream()
                .filter(m -> m.getCommentId().equals(commentId)
                        && m.getMentionedUserId().equals(alice.getId()))
                .findFirst().orElseThrow().getId();
        assertThat(aliceRowAfter).isEqualTo(aliceMentionIdBefore);

        assertThat(mentions.findMentionedUserIdsByCommentId(commentId))
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    // ---- 3. Update removes alice --------------------------------------------

    @Test
    @DisplayName("End-to-end: editing a comment to drop @alice removes her Mention row; she stops seeing the comment on /mentions")
    void update_removesMention_listingShrinks() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "@alice please review");

        // Alice currently sees 1 mention.
        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // Edit to drop alice.
        mvc.perform(patch("/tickets/{tid}/comments/{cid}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"never mind\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers.length()").value(0));

        assertThat(mentions.findMentionedUserIdsByCommentId(commentId)).isEmpty();
        // Alice's mention page now empty.
        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- 4. Hard delete cascades to mentions --------------------------------

    @Test
    @DisplayName("End-to-end: DELETE comment removes its mentions (Session 10 D5) and alice's /mentions shrinks")
    void delete_removesMentions_listingShrinks() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "@alice hello");

        mvc.perform(delete("/tickets/{tid}/comments/{cid}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(mentions.findMentionedUserIdsByCommentId(commentId)).isEmpty();
        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ---- 5. Pagination + newest-first sort ----------------------------------

    @Test
    @DisplayName("Spec 09 §3 + Session 10 D11: /mentions returns newest-first by mention.created_at across multiple pages")
    void listing_paginatesAndSortsNewestFirst() throws Exception {
        String token = login("mt_author", "pw");
        long c1 = createComment(token, "@alice first");
        sleepMs(10);
        long c2 = createComment(token, "@alice second");
        sleepMs(10);
        long c3 = createComment(token, "@alice third");

        // pageSize=2 → page 1 returns c3, c2; page 2 returns c1.
        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value((int) c3))
                .andExpect(jsonPath("$.data[1].id").value((int) c2));

        mvc.perform(get("/users/{uid}/mentions", alice.getId())
                        .header("Authorization", "Bearer " + token)
                        .param("page", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) c1));
    }

    // ---- 6. Audit (Session 10 D10): COMMENT.UPDATE row, no MENTION.* rows ---

    @Test
    @DisplayName("Session 10 D10: editing a comment that adds a mention writes one COMMENT.UPDATE audit row and NO MENTION.* rows")
    void audit_noMentionRowsEmitted() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "@alice please review");
        // Reset audit table to make the assertions clean (the create above
        // wrote a COMMENT.CREATE row that's not part of this assertion).
        auditLogs.deleteAll();

        mvc.perform(patch("/tickets/{tid}/comments/{cid}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"@alice @bob please review\",\"version\":0}"))
                .andExpect(status().isOk());

        var rows = auditLogs.findAll();
        // Exactly one row: COMMENT.UPDATE. Mentions are derived; no MENTION
        // entity type was even introduced (spec D10 + EntityType enum stayed
        // at slice-7's six members).
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEntityType()).isEqualTo(EntityType.COMMENT);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(rows.get(0).getEntityId()).isEqualTo(commentId);
    }

    // ---- 7. Self-mention (spec 09 §6) ---------------------------------------

    @Test
    @DisplayName("Spec 09 §6: mentioning yourself works end-to-end — no special case in the extractor or store")
    void selfMention_allowed() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "note to self @" + author.getUsername());

        // The author's own mention row landed.
        assertThat(mentions.findMentionedUserIdsByCommentId(commentId))
                .containsExactly(author.getId());

        // GET /mentions for the author returns the comment.
        mvc.perform(get("/users/{uid}/mentions", author.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) commentId));
    }

    // ---- 8. Unknown handles silently ignored (spec 09 §Extraction) ----------

    @Test
    @DisplayName("Spec 09 §Extraction: unknown handle in content is silently ignored; only resolved users land as mentions")
    void unknownHandle_silentlyIgnored() throws Exception {
        String token = login("mt_author", "pw");
        long commentId = createComment(token, "hi @ghost and @alice");

        // Only alice landed; ghost silently dropped.
        assertThat(mentions.findMentionedUserIdsByCommentId(commentId))
                .containsExactly(alice.getId());
    }

    // ---- helpers ------------------------------------------------------------

    private long createComment(String token, String content) throws Exception {
        MvcResult result = mvc.perform(post("/tickets/{tid}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + escape(content) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String login(String username, String password) throws Exception {
        String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private User persistUser(String username, String email, Role role, String pw) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(pw));
        return users.save(u);
    }

    private Project persistProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("d");
        p.setOwnerId(ownerId);
        return projects.save(p);
    }

    private Ticket persistTicket(Long projectId) {
        Ticket t = new Ticket();
        t.setTitle("T");
        t.setDescription("d");
        t.setProjectId(projectId);
        t.setStatus(TicketStatus.TODO);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setOverdue(false);
        return tickets.save(t);
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

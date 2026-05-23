package com.att.tdp.issueflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * Cross-cutting proof that every state-changing API writes an {@link AuditLog}
 * row (spec 06 §1). Decision Session 07 D9: instead of editing 30 existing
 * service tests with per-test {@code verify(auditLog).log(...)} calls, we
 * run real end-to-end requests through the actual {@code SecurityFilterChain}
 * + {@code AuditLogService} + JPA, then read the {@code audit_logs} table
 * back.
 *
 * <p>One assertion per state-changing operation:
 * <ul>
 *   <li>USER: create / update / delete (ADMIN-only)</li>
 *   <li>PROJECT: create / update / delete</li>
 *   <li>TICKET: create / update / delete</li>
 *   <li>COMMENT: create / update / delete</li>
 *   <li>AUTH: login / logout</li>
 * </ul>
 *
 * <p>Each test seeds its own fixtures, performs the operation, and asserts
 * exactly which row (action + entityType + actor) appeared in the audit
 * table. Read endpoints are NOT exercised here — they're not state-changing,
 * so they don't audit (spec §1).
 *
 * <p>Not {@code @Transactional} (unlike {@code SecurityIntegrationTest}):
 * because the audit row is written in the same transaction as the change,
 * a test-class-wide rollback would also wipe the audit row before the
 * post-call read can see it. Each test manually cleans up in
 * {@link #wipe()} for isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditIntegrationTest {

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
    private AuditLogRepository auditLogs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User dev;

    @BeforeEach
    void wipe() {
        // Order matters: tickets reference projects + users (FK-by-id, no JPA
        // association, but logical dependency); comments reference tickets
        // (slice 6, hard delete). The CommentRepository isn't injected here
        // because comments table is touched only inside the per-test sections
        // that need it — we delete any leftovers via the ticket repository's
        // cascade-less deleteAll (none here) plus an explicit comment wipe
        // when we land on the COMMENT tests.
        auditLogs.deleteAll();
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();

        admin = persist("admin-it", "admin-it@example.com", Role.ADMIN, "admin-pw");
        dev = persist("dev-it", "dev-it@example.com", Role.DEVELOPER, "dev-pw");

        // The admin seed insert above ALSO writes an audit row via the wired
        // UserService? No — we go through the repository, bypassing the service
        // wiring, which is exactly why we can reuse the SecurityIntegrationTest
        // pattern. Drain anything that did slip through (e.g. AdminSeeder if a
        // misconfig enables it in this profile).
        auditLogs.deleteAll();
    }

    private User persist(String username, String email, Role role, String pw) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(pw));
        return users.save(u);
    }

    private String login(String username, String password) throws Exception {
        String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = json.readTree(result.getResponse().getContentAsString());
        return payload.get("accessToken").asText();
    }

    // ---- LOGIN / LOGOUT -----------------------------------------------------

    @Test
    @DisplayName("AUTH login → 1 row {LOGIN, USER, performedBy=user.id, actor=USER}")
    void login_writesAuditRow() throws Exception {
        login("admin-it", "admin-pw");

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN);
        assertThat(row.getEntityType()).isEqualTo(EntityType.USER);
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("AUTH logout → 1 row {LOGOUT, USER, performedBy=user.id, actor=USER}")
    void logout_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        auditLogs.deleteAll(); // drop the login row so we assert on logout in isolation

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.LOGOUT);
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(dev.getId());
    }

    // ---- USER ---------------------------------------------------------------

    @Test
    @DisplayName("USER create via POST /users → {CREATE, USER, performedBy=admin.id}")
    void userCreate_writesAuditRow() throws Exception {
        String token = login("admin-it", "admin-pw");
        auditLogs.deleteAll();

        String body = """
                {"username":"newuser","email":"new@example.com",
                 "fullName":"New User","role":"DEVELOPER","password":"s3cret-pw"}
                """;
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.CREATE, EntityType.USER, admin.getId());
    }

    @Test
    @DisplayName("USER update via PATCH /users/{id} → {UPDATE, USER, performedBy=admin.id}")
    void userUpdate_writesAuditRow() throws Exception {
        String token = login("admin-it", "admin-pw");
        auditLogs.deleteAll();

        // User update lives at POST /users/update/{id} (non-RESTful but per
        // existing controller — slice 2). All other entities use PATCH.
        String body = "{\"fullName\":\"Renamed\",\"role\":\"DEVELOPER\"}";
        mvc.perform(post("/users/update/{id}", dev.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.UPDATE, EntityType.USER, admin.getId());
    }

    @Test
    @DisplayName("USER delete via DELETE /users/{id} → {DELETE, USER, performedBy=admin.id}")
    void userDelete_writesAuditRow() throws Exception {
        String token = login("admin-it", "admin-pw");
        // Create a throwaway user so deleting it doesn't break later tests.
        User target = persist("throwaway", "throwaway@example.com", Role.DEVELOPER, "pw");
        auditLogs.deleteAll();

        mvc.perform(delete("/users/{id}", target.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertOneRow(AuditAction.DELETE, EntityType.USER, admin.getId());
    }

    // ---- PROJECT ------------------------------------------------------------

    @Test
    @DisplayName("PROJECT create via POST /projects → {CREATE, PROJECT, performedBy=dev.id}")
    void projectCreate_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        auditLogs.deleteAll();

        String body = String.format(
                "{\"name\":\"P-1\",\"description\":\"d\",\"ownerId\":%d}", dev.getId());
        mvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.CREATE, EntityType.PROJECT, dev.getId());
    }

    @Test
    @DisplayName("PROJECT update via PATCH /projects/{id} → {UPDATE, PROJECT, performedBy=dev.id}")
    void projectUpdate_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-2", dev.getId());
        auditLogs.deleteAll();

        mvc.perform(patch("/projects/{id}", p.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"updated\"}"))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.UPDATE, EntityType.PROJECT, dev.getId());
    }

    @Test
    @DisplayName("PROJECT delete via DELETE /projects/{id} → {DELETE, PROJECT, performedBy=dev.id}")
    void projectDelete_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-3", dev.getId());
        auditLogs.deleteAll();

        mvc.perform(delete("/projects/{id}", p.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertOneRow(AuditAction.DELETE, EntityType.PROJECT, dev.getId());
    }

    // ---- TICKET -------------------------------------------------------------

    @Test
    @DisplayName("TICKET create via POST /tickets → {CREATE, TICKET, performedBy=dev.id}")
    void ticketCreate_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-T1", dev.getId());
        auditLogs.deleteAll();

        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}", p.getId());
        mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.CREATE, EntityType.TICKET, dev.getId());
    }

    @Test
    @DisplayName("TICKET update (status transition) → {UPDATE, TICKET} with diff populated")
    void ticketUpdate_writesAuditRow_withDiff() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-T2", dev.getId());
        Ticket t = persistTicket(p.getId(), TicketStatus.TODO);
        auditLogs.deleteAll();

        String body = "{\"status\":\"IN_PROGRESS\",\"version\":0}";
        mvc.perform(patch("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.TICKET);
        // D3: status transitions get a diff string; verify the FSM shape.
        assertThat(row.getDiff()).contains("\"from\":\"TODO\"").contains("\"to\":\"IN_PROGRESS\"");
    }

    @Test
    @DisplayName("TICKET delete via DELETE /tickets/{id} → {DELETE, TICKET, performedBy=dev.id}")
    void ticketDelete_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-T3", dev.getId());
        Ticket t = persistTicket(p.getId(), TicketStatus.TODO);
        auditLogs.deleteAll();

        mvc.perform(delete("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertOneRow(AuditAction.DELETE, EntityType.TICKET, dev.getId());
    }

    // ---- COMMENT ------------------------------------------------------------

    @Test
    @DisplayName("COMMENT create via POST /tickets/{tid}/comments → {CREATE, COMMENT, performedBy=dev.id}")
    void commentCreate_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-C1", dev.getId());
        Ticket t = persistTicket(p.getId(), TicketStatus.TODO);
        auditLogs.deleteAll();

        mvc.perform(post("/tickets/{tid}/comments", t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.CREATE, EntityType.COMMENT, dev.getId());
    }

    @Test
    @DisplayName("COMMENT update via PATCH /tickets/{tid}/comments/{cid} → {UPDATE, COMMENT}")
    void commentUpdate_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-C2", dev.getId());
        Ticket t = persistTicket(p.getId(), TicketStatus.TODO);
        // Create a comment via the API so we exercise the same surface.
        MvcResult created = mvc.perform(post("/tickets/{tid}/comments", t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long commentId = json.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();
        auditLogs.deleteAll();

        mvc.perform(patch("/tickets/{tid}/comments/{cid}", t.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited\",\"version\":0}"))
                .andExpect(status().isOk());

        assertOneRow(AuditAction.UPDATE, EntityType.COMMENT, dev.getId());
    }

    @Test
    @DisplayName("COMMENT delete via DELETE → {DELETE, COMMENT, performedBy=dev.id}")
    void commentDelete_writesAuditRow() throws Exception {
        String token = login("dev-it", "dev-pw");
        Project p = persistProject("P-C3", dev.getId());
        Ticket t = persistTicket(p.getId(), TicketStatus.TODO);
        MvcResult created = mvc.perform(post("/tickets/{tid}/comments", t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long commentId = json.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();
        auditLogs.deleteAll();

        mvc.perform(delete("/tickets/{tid}/comments/{cid}", t.getId(), commentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertOneRow(AuditAction.DELETE, EntityType.COMMENT, dev.getId());
    }

    // ---- GET /audit-logs (RBAC + happy path with real filter chain) --------

    @Test
    @DisplayName("GET /audit-logs as DEVELOPER → 403 AUTH_FORBIDDEN (spec 06 §4)")
    void adminOnly_403ForDeveloper() throws Exception {
        String token = login("dev-it", "dev-pw");

        mvc.perform(get("/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /audit-logs as ADMIN → 200 with PageResponse envelope and audited rows")
    void adminCanList_seesAuditRows() throws Exception {
        // login itself writes a row — this proves the envelope shape end-to-end.
        String token = login("admin-it", "admin-pw");

        mvc.perform(get("/audit-logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                // The admin-it LOGIN row is in here at least.
                .andExpect(jsonPath("$.items[?(@.action == 'LOGIN')]").exists());
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Asserts there is exactly one row with the given action + entityType,
     * and that the actor is USER + performedBy matches.
     */
    private void assertOneRow(AuditAction action, EntityType entityType, Long performedBy) {
        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(action);
        assertThat(row.getEntityType()).isEqualTo(entityType);
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(performedBy);
    }

    private Project persistProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("d");
        p.setOwnerId(ownerId);
        return projects.save(p);
    }

    private Ticket persistTicket(Long projectId, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle("T");
        t.setDescription("d");
        t.setProjectId(projectId);
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setOverdue(false);
        return tickets.save(t);
    }
}

package com.att.tdp.issueflow.softdelete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.dependencies.repository.TicketDependencyRepository;
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
 * End-to-end proof for slice-9 cross-cutting properties that the unit /
 * @WebMvcTest tests can't reach on their own.
 *
 * <p>Three classes of assertion:
 * <ol>
 *   <li><b>Cross-cutting filter behavior:</b> a soft-deleted ticket disappears
 *       from {@code GET /tickets?projectId=...}, {@code GET /tickets/{id}}
 *       returns 404, {@code PATCH} returns 404. Spec 08 §2.</li>
 *   <li><b>RBAC:</b> {@code GET /{resource}/deleted} and
 *       {@code POST /{resource}/{id}/restore} are 403 {@code AUTH_FORBIDDEN}
 *       for a DEVELOPER, 200 for an ADMIN. Spec 08 §5. (Per slice-7 Gotcha:
 *       @WebMvcTest can't test 403, so this lives here with the real security
 *       chain.)</li>
 *   <li><b>Cross-feature with slice 8 (dependencies):</b> a soft-deleted
 *       blocker no longer blocks the dependent ticket's DONE transition (the
 *       JPQL JOIN in countOpenBlockers is filtered by @SQLRestriction).
 *       Session 09 D7.</li>
 *   <li><b>Audit:</b> soft-delete writes AuditAction.DELETE, restore writes
 *       AuditAction.RESTORE. Spec 08 §7.</li>
 * </ol>
 *
 * <p>Pattern + isolation strategy mirrors {@code AuditIntegrationTest}
 * (Session 07 D9 + slice-7 H2 cross-class Gotcha): {@code @BeforeEach} wipes
 * all relevant tables before each test, so other test classes leaving rows in
 * the H2 in-mem DB don't break us.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SoftDeleteIntegrationTest {

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
    private TicketDependencyRepository deps;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User dev;

    @BeforeEach
    void wipe() {
        // Order: dep edges → tickets → projects → users; audit last.
        deps.deleteAll();
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();

        admin = persistUser("sd-admin", "sd-admin@example.com", Role.ADMIN, "admin-pw");
        dev = persistUser("sd-dev", "sd-dev@example.com", Role.DEVELOPER, "dev-pw");
        auditLogs.deleteAll();
    }

    // ---- cross-cutting: soft-deleted tickets are invisible everywhere ------

    @Test
    @DisplayName("Spec 08 §2: soft-deleted ticket → 404 on GET, 404 on PATCH, missing from list (existing reads UNCHANGED, get auto-filter)")
    void deletedTicket_invisibleToAllStandardReads() throws Exception {
        String token = login("sd-dev", "dev-pw");
        Project p = persistProject("P-cross", dev.getId());
        Ticket alive = persistTicket(p.getId(), "alive", TicketStatus.TODO);
        Ticket doomed = persistTicket(p.getId(), "doomed", TicketStatus.TODO);

        mvc.perform(delete("/tickets/{id}", doomed.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // GET /tickets/{id} → 404
        mvc.perform(get("/tickets/{id}", doomed.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        // PATCH /tickets/{id} → 404 (no zombie writes possible)
        mvc.perform(patch("/tickets/{id}", doomed.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"zombie\",\"version\":0}"))
                .andExpect(status().isNotFound());

        // GET /tickets?projectId=X excludes the deleted one
        MvcResult list = mvc.perform(get("/tickets").param("projectId", p.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = json.readTree(list.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(1);
        assertThat(arr.get(0).get("id").asLong()).isEqualTo(alive.getId());

        // ... but the row PHYSICALLY exists (deleted_at != NULL)
        assertThat(tickets.existsByIdIncludingDeleted(doomed.getId())).isTrue();
    }

    @Test
    @DisplayName("Spec 08 §1 + §7: soft-delete writes AuditAction.DELETE (same as hard-delete from client view)")
    void softDelete_writesAuditDeleteRow() throws Exception {
        String token = login("sd-dev", "dev-pw");
        Project p = persistProject("P-aud", dev.getId());
        Ticket t = persistTicket(p.getId(), "audit-me", TicketStatus.TODO);
        auditLogs.deleteAll();

        mvc.perform(delete("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(rows.get(0).getEntityType()).isEqualTo(EntityType.TICKET);
        assertThat(rows.get(0).getEntityId()).isEqualTo(t.getId());
    }

    // ---- RBAC --------------------------------------------------------------

    @Test
    @DisplayName("Spec 08 §5: DEVELOPER hitting GET /tickets/deleted → 403 AUTH_FORBIDDEN")
    void deletedListing_forbiddenForDeveloper() throws Exception {
        String token = login("sd-dev", "dev-pw");
        Project p = persistProject("P-rbac", dev.getId());

        mvc.perform(get("/tickets/deleted").param("projectId", p.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("Spec 08 §5: DEVELOPER hitting POST /tickets/{id}/restore → 403 AUTH_FORBIDDEN")
    void restoreTicket_forbiddenForDeveloper() throws Exception {
        String token = login("sd-dev", "dev-pw");

        mvc.perform(post("/tickets/{id}/restore", 1L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("Spec 08 §5: DEVELOPER hitting GET /projects/deleted → 403 AUTH_FORBIDDEN")
    void deletedProjectListing_forbiddenForDeveloper() throws Exception {
        String token = login("sd-dev", "dev-pw");

        mvc.perform(get("/projects/deleted").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("Spec 08 §5: DEVELOPER hitting POST /projects/{id}/restore → 403 AUTH_FORBIDDEN")
    void restoreProject_forbiddenForDeveloper() throws Exception {
        String token = login("sd-dev", "dev-pw");

        mvc.perform(post("/projects/{id}/restore", 1L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    // ---- ADMIN happy paths -------------------------------------------------

    @Test
    @DisplayName("ADMIN can list deleted tickets and restore one — writes audit RESTORE")
    void admin_canListAndRestoreTickets() throws Exception {
        String adminToken = login("sd-admin", "admin-pw");
        String devToken = login("sd-dev", "dev-pw");

        Project p = persistProject("P-restore", dev.getId());
        Ticket t = persistTicket(p.getId(), "restore-me", TicketStatus.TODO);

        // dev soft-deletes
        mvc.perform(delete("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
        auditLogs.deleteAll();

        // admin lists deleted → sees it
        mvc.perform(get("/tickets/deleted").param("projectId", p.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(t.getId()))
                .andExpect(jsonPath("$[0].deletedAt").exists());

        // admin restores → 200 + audit RESTORE
        mvc.perform(post("/tickets/{id}/restore", t.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(t.getId()))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.RESTORE);
        assertThat(rows.get(0).getEntityType()).isEqualTo(EntityType.TICKET);
        assertThat(rows.get(0).getPerformedBy()).isEqualTo(admin.getId());

        // post-restore: GET works again
        mvc.perform(get("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Spec 08 §6: restoring an already-active ticket → 409 ALREADY_ACTIVE")
    void admin_restoreAlreadyActive_returns409() throws Exception {
        String adminToken = login("sd-admin", "admin-pw");
        Project p = persistProject("P-conf", dev.getId());
        Ticket t = persistTicket(p.getId(), "still-alive", TicketStatus.TODO);

        mvc.perform(post("/tickets/{id}/restore", t.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ACTIVE"));
    }

    // ---- ADR 0002: no cascade on project soft-delete -----------------------

    @Test
    @DisplayName("ADR 0002: soft-deleting a project does NOT cascade deletedAt to its tickets")
    void projectDelete_doesNotCascadeToTickets() throws Exception {
        String devToken = login("sd-dev", "dev-pw");
        Project p = persistProject("P-cascade", dev.getId());
        Ticket t = persistTicket(p.getId(), "should-survive", TicketStatus.TODO);

        mvc.perform(delete("/projects/{id}", p.getId())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());

        // The ticket's deleted_at is still NULL (no cascade). Use the native
        // bypass to read past the project's @SQLRestriction filter on this
        // ticket's project_id.
        Ticket reloaded = tickets.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    // ---- cross-feature with slice 8: soft-deleted blocker stops blocking ---

    @Test
    @DisplayName("Session 09 D7: soft-deleted blocker no longer blocks dependent ticket's DONE transition")
    void softDeletedBlocker_doesNotBlockDone() throws Exception {
        String devToken = login("sd-dev", "dev-pw");
        String adminToken = login("sd-admin", "admin-pw");
        Project p = persistProject("P-cf", dev.getId());
        Ticket subject = persistTicket(p.getId(), "subj", TicketStatus.IN_REVIEW);
        Ticket blocker = persistTicket(p.getId(), "blk", TicketStatus.IN_PROGRESS); // NOT done

        // Add a dependency: subject is blocked by blocker.
        mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", blocker.getId())))
                .andExpect(status().isCreated());

        // Transition to DONE → rejected by slice 8's cross-feature check.
        mvc.perform(patch("/tickets/{id}", subject.getId())
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_HAS_OPEN_BLOCKERS"));

        // Soft-delete the blocker.
        mvc.perform(delete("/tickets/{id}", blocker.getId())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());

        // Now the subject can transition to DONE — countOpenBlockers' JPQL
        // JOIN to Ticket is filtered by @SQLRestriction, so the deleted
        // blocker is invisible to the open-blocker count.
        mvc.perform(patch("/tickets/{id}", subject.getId())
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // Sanity: admin can still see the blocker via /deleted.
        mvc.perform(get("/tickets/deleted").param("projectId", p.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(blocker.getId()));
    }

    // ---- helpers -----------------------------------------------------------

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

    private Ticket persistTicket(Long projectId, String title, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription("d");
        t.setProjectId(projectId);
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setOverdue(false);
        return tickets.save(t);
    }
}

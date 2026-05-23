package com.att.tdp.issueflow.dependencies;

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
 * End-to-end proof for the two cross-cutting properties that
 * {@code DependencyServiceTest} (Mockito) and
 * {@code DependencyControllerWebMvcTest} can't reach on their own:
 *
 * <ol>
 *   <li><b>Cross-feature DONE-blocker:</b> the actual
 *       {@code TicketService.update(...)} (slice 5) calls back into
 *       {@code DependencyService.hasOpenBlockers(...)} (slice 8) and returns
 *       409 {@code TICKET_HAS_OPEN_BLOCKERS} on a real HTTP request when a
 *       blocker is non-DONE — and ALLOWS the transition once the blocker
 *       moves to DONE. Both arms exercised in
 *       {@link #ticketTransitionToDone_blockedByOpenBlocker} and
 *       {@link #ticketTransitionToDone_allowedOnceBlockerIsDone}.</li>
 *   <li><b>Audit rows actually land:</b> {@code POST /dependencies} +
 *       {@code DELETE /dependencies/{bid}} each write the expected
 *       audit row with {@code entityType=DEPENDENCY} and the surrogate id.
 *       Mockito tests verify the call; this test verifies the call survives
 *       the JPA transaction boundary.</li>
 * </ol>
 *
 * <p>Pattern + isolation strategy mirrors {@code AuditIntegrationTest}
 * (Session 07 D9 + the slice-7 H2 cross-class Gotcha): {@code @BeforeEach}
 * wipes both audit and dependency tables before each test so order is
 * irrelevant.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DependencyIntegrationTest {

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

    private User dev;

    @BeforeEach
    void wipe() {
        // Order: deps -> tickets -> projects -> users -> audit (last so we
        // don't audit the cleanup itself; the cleanup uses repository.delete
        // which bypasses the audited service layer).
        deps.deleteAll();
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();

        dev = persistUser("dep-dev", "dep-dev@example.com", Role.DEVELOPER, "dev-pw");
        auditLogs.deleteAll(); // drop anything an AdminSeeder might have written
    }

    // ---- cross-feature: TicketService.update DONE-blocker check ------------

    @Test
    @DisplayName("Spec 04 §9 — PATCH /tickets/{id} to DONE returns 409 TICKET_HAS_OPEN_BLOCKERS when a blocker is non-DONE")
    void ticketTransitionToDone_blockedByOpenBlocker() throws Exception {
        String token = login("dep-dev", "dev-pw");
        Project p = persistProject("P-blk", dev.getId());
        Ticket subject = persistTicket(p.getId(), TicketStatus.IN_REVIEW);
        Ticket blocker = persistTicket(p.getId(), TicketStatus.IN_PROGRESS); // NOT done

        // Add the dependency via the actual API so audit / save paths run.
        mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", blocker.getId())))
                .andExpect(status().isCreated());

        // Try to mark the subject DONE — should be rejected.
        mvc.perform(patch("/tickets/{id}", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_HAS_OPEN_BLOCKERS"));

        // The subject's status is still IN_REVIEW — the guard ran BEFORE setStatus.
        Ticket reloaded = tickets.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("Spec 04 §9 — once the blocker reaches DONE itself, the subject can also transition to DONE")
    void ticketTransitionToDone_allowedOnceBlockerIsDone() throws Exception {
        String token = login("dep-dev", "dev-pw");
        Project p = persistProject("P-clr", dev.getId());
        Ticket subject = persistTicket(p.getId(), TicketStatus.IN_REVIEW);
        Ticket blocker = persistTicket(p.getId(), TicketStatus.IN_REVIEW);

        mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", blocker.getId())))
                .andExpect(status().isCreated());

        // Confirm "still blocked" first.
        mvc.perform(patch("/tickets/{id}", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isConflict());

        // Move the blocker to DONE.
        mvc.perform(patch("/tickets/{id}", blocker.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isOk());

        // Now the subject can transition.
        mvc.perform(patch("/tickets/{id}", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    // ---- audit-row proof for DEPENDENCY entityType --------------------------

    @Test
    @DisplayName("POST /tickets/{tid}/dependencies → audit row {CREATE, DEPENDENCY, performedBy=dev.id, entityId=dep.id}")
    void addDependency_writesAuditRow() throws Exception {
        String token = login("dep-dev", "dev-pw");
        Project p = persistProject("P-A", dev.getId());
        Ticket subject = persistTicket(p.getId(), TicketStatus.TODO);
        Ticket blocker = persistTicket(p.getId(), TicketStatus.TODO);
        auditLogs.deleteAll();

        MvcResult created = mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", blocker.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode payload = json.readTree(created.getResponse().getContentAsString());
        long depId = payload.get("id").asLong();

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.DEPENDENCY);
        assertThat(row.getEntityId()).isEqualTo(depId);
        assertThat(row.getPerformedBy()).isEqualTo(dev.getId());
    }

    @Test
    @DisplayName("DELETE /tickets/{tid}/dependencies/{bid} → audit row {DELETE, DEPENDENCY, entityId=dep.id}")
    void removeDependency_writesAuditRow() throws Exception {
        String token = login("dep-dev", "dev-pw");
        Project p = persistProject("P-D", dev.getId());
        Ticket subject = persistTicket(p.getId(), TicketStatus.TODO);
        Ticket blocker = persistTicket(p.getId(), TicketStatus.TODO);
        MvcResult created = mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", blocker.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        long depId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        auditLogs.deleteAll();

        mvc.perform(delete("/tickets/{tid}/dependencies/{bid}",
                        subject.getId(), blocker.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.DEPENDENCY);
        assertThat(row.getEntityId()).isEqualTo(depId);
    }

    // ---- happy-path GET surface (smoke test through real chain) -------------

    @Test
    @DisplayName("GET /tickets/{tid}/dependencies returns blocker summaries in id-asc order")
    void getBlockers_returnsArrayShape() throws Exception {
        String token = login("dep-dev", "dev-pw");
        Project p = persistProject("P-G", dev.getId());
        Ticket subject = persistTicket(p.getId(), TicketStatus.TODO);
        Ticket b1 = persistTicket(p.getId(), TicketStatus.TODO);
        Ticket b2 = persistTicket(p.getId(), TicketStatus.IN_PROGRESS);

        mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", b1.getId())))
                .andExpect(status().isCreated());
        mvc.perform(post("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"blockedBy\":%d}", b2.getId())))
                .andExpect(status().isCreated());

        mvc.perform(get("/tickets/{tid}/dependencies", subject.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(b1.getId().intValue()))
                .andExpect(jsonPath("$[1].id").value(b2.getId().intValue()));
    }

    // ---- helpers ------------------------------------------------------------

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

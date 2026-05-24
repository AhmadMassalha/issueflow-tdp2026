package com.att.tdp.issueflow.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end proofs for slice 13 — the integration-only properties that
 * the Mockito + WebMvc tests can't reach:
 *
 * <ol>
 *   <li>Spec 12 §1 + §4 atomicity: POST /tickets without {@code assigneeId}
 *       writes BOTH the CREATE/USER row AND the AUTO_ASSIGN/SYSTEM row in
 *       the SAME transaction (one rollback = both gone). End-to-end via
 *       the real wire, real Hibernate, real audit-log persistence.</li>
 *   <li>Spec 12 §Algorithm point 3 — tie-break by lowest userId works
 *       through real JPQL (NOT a mock). Two devs with equal load → the
 *       earlier-registered one wins.</li>
 *   <li>ADR 0007 — project owner is a candidate even without any tickets
 *       in the project (bootstrap case).</li>
 *   <li>Spec 12 §Algorithm point 4 — project with no eligible developers
 *       falls through to {@code assigneeId = null} with NO AUTO_ASSIGN
 *       audit row (the system DIDN'T assign anyone).</li>
 *   <li>Spec 12 §2 — ADMINs are excluded from workload AND from the
 *       auto-assign candidate set even if they own the project.</li>
 *   <li>Slice 11 inheritance: CSV import gets auto-assignment for free
 *       through {@code CsvImportRowExecutor} (no new code there) — each
 *       imported row gets the CREATE + AUTO_ASSIGN audit pair.</li>
 *   <li>Spec 12 §3 — workload endpoint returns 404 on soft-deleted project
 *       via the real {@code @SQLRestriction} on {@code Project}.</li>
 *   <li>Session 13 D7 — supplied assignee that is a DEVELOPER but NOT a
 *       member of the project → 422 INVALID_ASSIGNEE end-to-end.</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code AuditIntegrationTest} / {@code CsvIntegrationTest}
 * (not {@code @Transactional}, manual wipe in {@code @BeforeEach}) so the
 * audit-row assertions see committed state.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoAssignIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ProjectRepository projects;
    @Autowired private TicketRepository tickets;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void wipe() {
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();
    }

    // ---- spec §1 + §4 — two audit rows in one tx --------------------------

    @Test
    @DisplayName("Spec 12 §1 + §4 — POST /tickets without assigneeId auto-assigns AND writes CREATE + AUTO_ASSIGN in one tx")
    void autoAssignedTicket_writesTwoAuditRowsInOneTx() throws Exception {
        User dev = persistUser("auto_dev_1", Role.DEVELOPER);
        Project p = persistProject("auto-p-1", dev.getId());
        String token = login("auto_dev_1");
        // Clear the LOGIN/USER audit row that /auth/login just wrote so the
        // assertion below counts only the rows from the ticket-create call.
        auditLogs.deleteAll();

        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}",
                p.getId());

        MvcResult posted = mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(dev.getId().intValue()))
                .andReturn();
        long ticketId = json.readTree(posted.getResponse().getContentAsString())
                .get("id").asLong();

        List<AuditLog> rows = auditLogs.findAll();
        // CREATE/USER + AUTO_ASSIGN/SYSTEM — exactly two rows, same entity id.
        assertThat(rows).hasSize(2);

        AuditLog create = rows.stream()
                .filter(r -> r.getAction() == AuditAction.CREATE).findFirst().orElseThrow();
        assertThat(create.getActor()).isEqualTo(Actor.USER);
        assertThat(create.getPerformedBy()).isEqualTo(dev.getId());
        assertThat(create.getEntityId()).isEqualTo(ticketId);
        assertThat(create.getEntityType()).isEqualTo(EntityType.TICKET);

        AuditLog auto = rows.stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN).findFirst().orElseThrow();
        assertThat(auto.getActor()).isEqualTo(Actor.SYSTEM);
        assertThat(auto.getPerformedBy()).isNull();
        assertThat(auto.getEntityId()).isEqualTo(ticketId);
        assertThat(auto.getEntityType()).isEqualTo(EntityType.TICKET);
        // Spec §1 diff shape verbatim.
        assertThat(auto.getDiff()).isEqualTo("{\"assigneeId\":" + dev.getId() + "}");
    }

    // ---- spec §Algorithm — real tie-break by lowest userId ---------------

    @Test
    @DisplayName("Spec 12 §Algorithm point 3 — real JPQL: tie-break by lowest userId when two devs have equal load")
    void autoAssign_tieBreakByLowestUserId() throws Exception {
        // Persist two devs. Owner is the second one; first one has no ticket
        // history — but is a DEVELOPER, so they qualify as a member only via
        // the owner OR-clause, which doesn't apply to them. To make BOTH
        // devs members, we add a ticket to each one's account in this
        // project before the test runs.
        User devLow = persistUser("auto_dev_low", Role.DEVELOPER);   // earlier registration → lower id
        User devHigh = persistUser("auto_dev_high", Role.DEVELOPER);
        Project p = persistProject("auto-p-tie", devLow.getId());     // make devLow a member via owner clause
        // Make devHigh a member via has-a-ticket; AND give devHigh the same
        // open-ticket count as devLow (currently 0). Seed a DONE ticket so
        // the membership EXISTS check fires but the open-count stays 0.
        persistTicketAssigned(p.getId(), devHigh.getId(), TicketStatus.DONE);

        String token = login("auto_dev_low");
        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}",
                p.getId());

        mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // devLow has the lower id AND tied count (both 0 — the DONE
                // ticket doesn't count toward open-ticket-count) → devLow wins.
                .andExpect(jsonPath("$.assigneeId").value(devLow.getId().intValue()));
    }

    // ---- ADR 0007 — owner is a bootstrap member --------------------------

    @Test
    @DisplayName("ADR 0007 — fresh project (zero tickets), owner-as-DEVELOPER is the lone candidate and gets auto-assigned")
    void autoAssign_ownerAsBootstrapMember() throws Exception {
        User dev = persistUser("auto_dev_owner", Role.DEVELOPER);
        Project p = persistProject("fresh-project", dev.getId());
        String token = login("auto_dev_owner");

        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}",
                p.getId());

        mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(dev.getId().intValue()));
    }

    // ---- spec §4 point 4 — no candidates → assigneeId null --------------

    @Test
    @DisplayName("Spec 12 §Algorithm point 4 — no eligible developers → assigneeId stays null, NO AUTO_ASSIGN audit row")
    void autoAssign_noCandidates_assigneeStaysNull() throws Exception {
        // Project owner is an ADMIN (excluded from candidates per spec §2).
        // No DEVELOPER has filed a ticket here. So the candidate set is empty.
        User admin = persistUser("auto_admin", Role.ADMIN);
        Project p = persistProject("admin-owned", admin.getId());
        String token = login("auto_admin");
        auditLogs.deleteAll(); // drop the LOGIN row so we only count post-create audits

        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}",
                p.getId());

        mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").doesNotExist());

        // Spec §1: AUTO_ASSIGN row is ONLY written when the system actually
        // assigned. No candidate → no row.
        assertThat(auditLogs.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN).count())
                .isZero();
    }

    // ---- spec §2 — ADMIN exclusion on workload endpoint -----------------

    @Test
    @DisplayName("Spec 12 §2 — workload endpoint excludes ADMIN owners and ADMIN ticket-history members")
    void workloadEndpoint_excludesAdmins() throws Exception {
        User admin = persistUser("workload_admin", Role.ADMIN);
        User dev = persistUser("workload_dev", Role.DEVELOPER);
        Project p = persistProject("workload-p", admin.getId());
        // Give the dev a ticket so they qualify as a member via has-a-ticket.
        persistTicketAssigned(p.getId(), dev.getId(), TicketStatus.IN_PROGRESS);
        // Also assign one to the admin to prove they're filtered out even when
        // they DO have ticket history.
        persistTicketAssigned(p.getId(), admin.getId(), TicketStatus.IN_PROGRESS);
        String token = login("workload_dev");

        mvc.perform(get("/projects/{id}/workload", p.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(dev.getId().intValue()))
                .andExpect(jsonPath("$[0].username").value("workload_dev"))
                .andExpect(jsonPath("$[0].openTicketCount").value(1));
    }

    // ---- spec §3 — workload endpoint on soft-deleted project = 404 ------

    @Test
    @DisplayName("Spec 12 §3 — workload endpoint returns 404 PROJECT_NOT_FOUND on soft-deleted project (via @SQLRestriction)")
    void workloadEndpoint_softDeletedProject_returns404() throws Exception {
        User dev = persistUser("workload_dev_sd", Role.DEVELOPER);
        Project p = persistProject("soft-deleted-project", dev.getId());
        String token = login("workload_dev_sd");

        // Soft-delete the project via the API (slice 9 @SQLDelete).
        mvc.perform(delete("/projects/{id}", p.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/projects/{id}/workload", p.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ---- slice 11 inheritance — CSV import gets auto-assign for free -----

    @Test
    @DisplayName("Slice 11 inheritance — CSV import auto-assigns rows without assigneeId; each gets CREATE + AUTO_ASSIGN audit pair")
    void csvImport_inheritsAutoAssign() throws Exception {
        User dev = persistUser("csv_auto_dev", Role.DEVELOPER);
        Project p = persistProject("csv-auto-p", dev.getId());
        String token = login("csv_auto_dev");
        auditLogs.deleteAll();

        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,FromCsv1,,,LOW,BUG,,
                ,FromCsv2,,,MEDIUM,FEATURE,,
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(p.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        // Both imported tickets actually have the assigneeId set by the
        // auto-assigner (slice 11's CsvImportRowExecutor calls
        // TicketService.create, which now invokes the assigner).
        List<Ticket> persisted = tickets.findByProjectId(p.getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).allSatisfy(t -> assertThat(t.getAssigneeId()).isEqualTo(dev.getId()));

        // Each ticket → CREATE + AUTO_ASSIGN row (4 rows total).
        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().filter(r -> r.getAction() == AuditAction.CREATE).count()).isEqualTo(2);
        assertThat(rows.stream().filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN).count()).isEqualTo(2);
    }

    // ---- Session 13 D7 — supplied non-member assignee → 422 -------------

    @Test
    @DisplayName("Session 13 D7 — supplied assigneeId is a DEVELOPER but NOT a project member → 422 INVALID_ASSIGNEE end-to-end")
    void create_suppliedAssigneeNotMember_returns422() throws Exception {
        User devOwner = persistUser("d7_owner", Role.DEVELOPER);
        User devOutsider = persistUser("d7_outsider", Role.DEVELOPER);
        Project p = persistProject("d7-project", devOwner.getId());
        // devOutsider has no tickets in this project AND isn't the owner → not a member.
        String token = login("d7_owner");
        auditLogs.deleteAll(); // drop the LOGIN row before the create attempt

        String body = String.format(
                "{\"title\":\"T\",\"description\":\"d\",\"projectId\":%d,"
                        + "\"assigneeId\":%d,\"priority\":\"MEDIUM\",\"type\":\"FEATURE\"}",
                p.getId(), devOutsider.getId());

        mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));

        // The validation fails BEFORE the @Transactional persist boundary,
        // so neither a ticket row nor any audit row lands.
        assertThat(tickets.count()).isZero();
        assertThat(auditLogs.count()).isZero();
    }

    // ---- helpers ----------------------------------------------------------

    private String login(String username) throws Exception {
        String body = String.format("{\"username\":\"%s\",\"password\":\"pw1234\"}", username);
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private User persistUser(String username, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode("pw1234"));
        return users.save(u);
    }

    private Project persistProject(String name, Long ownerId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("d");
        p.setOwnerId(ownerId);
        return projects.save(p);
    }

    private Ticket persistTicketAssigned(Long projectId, Long assigneeId, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle("seed-" + assigneeId);
        t.setDescription("seed");
        t.setProjectId(projectId);
        t.setAssigneeId(assigneeId);
        t.setStatus(status);
        t.setPriority(Priority.LOW);
        t.setType(TicketType.BUG);
        t.setOverdue(false);
        return tickets.save(t);
    }
}

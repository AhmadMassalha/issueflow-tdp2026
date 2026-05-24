package com.att.tdp.issueflow.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
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
 * End-to-end proof for the cross-cutting properties of the CSV slice
 * that the Mockito unit tests can't reach on their own:
 *
 * <ol>
 *   <li><b>Round-trip integrity:</b> what we export, we can re-import.
 *       Every well-formed row that came out goes back in (with new
 *       server-assigned ids — D10).</li>
 *   <li><b>Spec 10 §9 audit invariant:</b> each successfully-imported
 *       row writes exactly one {@code AuditAction.CREATE} audit row
 *       (free via the slice-7 wiring on {@code TicketService.create}).</li>
 *   <li><b>REQUIRES_NEW isolation:</b> a failing row REALLY does NOT
 *       roll back its sibling successful rows. Proves the per-row
 *       transaction boundary actually works through real Hibernate +
 *       JDBC, not just through a Mockito stub.</li>
 *   <li><b>Real Servlet 3+ streaming:</b> {@code StreamingResponseBody}
 *       async dispatch lands the same bytes the unit test wrote
 *       in-process.</li>
 *   <li><b>Wrong MIME + missing parts:</b> the production filter chain
 *       (security + multipart + exception advice) renders the right
 *       envelope.</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code AuditIntegrationTest} (Session 07 D9):
 * {@code @BeforeEach} wipes every relevant table and seeds a developer
 * + project so subsequent operations have a real context. NOT
 * {@code @Transactional} — the audit row would be rolled back too.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CsvIntegrationTest {

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

    private User dev;
    private Project project;
    private String token;

    @BeforeEach
    void wipe() throws Exception {
        // Order — children before parents (FK-by-id):
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();

        dev = persistUser("csv_dev", "csv_dev@example.com", Role.DEVELOPER, "pw1234");
        project = persistProject("csv-project", dev.getId());
        token = login("csv_dev", "pw1234");
        auditLogs.deleteAll(); // drop anything seed wrote
    }

    // ---- round-trip --------------------------------------------------------

    @Test
    @DisplayName("Round-trip: 2 created → exported → re-imported as 2 more (fresh ids per D10)")
    void roundTrip_exportThenReImport() throws Exception {
        // Seed two tickets through the REAL POST /tickets so the export
        // exercises the actual persistence + repository path.
        long firstId = createTicket("Alpha", "first ticket", "BUG", "LOW");
        long secondId = createTicket("Beta", "second ticket", "FEATURE", "HIGH");
        assertThat(tickets.count()).isEqualTo(2);

        // Export.
        String exported = export(project.getId());
        String[] lines = exported.split("\r?\n");
        assertThat(lines).hasSize(3); // header + 2 rows
        assertThat(lines[0]).isEqualTo("id,title,description,status,priority,type,assigneeId,dueDate");
        assertThat(lines[1]).startsWith(firstId + ",Alpha,");
        assertThat(lines[2]).startsWith(secondId + ",Beta,");

        // Re-import (id column should be silently ignored — fresh ids assigned).
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", exported.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        // 4 tickets total now: original 2 + 2 new with fresh ids.
        assertThat(tickets.count()).isEqualTo(4);
        // The originals are still there with their original ids:
        assertThat(tickets.findById(firstId)).isPresent();
        assertThat(tickets.findById(secondId)).isPresent();
    }

    // ---- spec §9 audit invariant ------------------------------------------

    @Test
    @DisplayName("Spec 10 §9 + slice 13: ONE CREATE row + ONE AUTO_ASSIGN row per imported ticket when auto-assignment fires")
    void importSuccess_writesOneAuditRowPerTicket() throws Exception {
        // Slice 13 inheritance: CSV rows without explicit `assigneeId` now
        // trigger AutoAssigner inside TicketService.create (via the per-row
        // REQUIRES_NEW wrapper in CsvImportRowExecutor — no new code there).
        // The `dev` fixture is the project owner AND a DEVELOPER (ADR 0007
        // bootstrap-member), so they're the sole candidate for every row →
        // each row writes 2 audit rows (CREATE/USER + AUTO_ASSIGN/SYSTEM).
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,Imp1,d1,,LOW,BUG,,
                ,Imp2,d2,,MEDIUM,FEATURE,,
                ,Imp3,d3,,HIGH,TECHNICAL,,
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3));

        List<AuditLog> rows = auditLogs.findAll();
        // 3 created tickets × 2 audit rows each = 6.
        assertThat(rows).hasSize(6);

        // Spec 10 §9 — one CREATE/USER row per imported ticket.
        List<AuditLog> createRows = rows.stream()
                .filter(r -> r.getAction() == AuditAction.CREATE)
                .toList();
        assertThat(createRows).hasSize(3);
        assertThat(createRows).allSatisfy(r -> {
            assertThat(r.getEntityType()).isEqualTo(EntityType.TICKET);
            assertThat(r.getActor()).isEqualTo(Actor.USER);
            assertThat(r.getPerformedBy()).isEqualTo(dev.getId());
        });

        // Spec 12 §1 — one AUTO_ASSIGN/SYSTEM row per imported ticket
        // (because each ticket was actually auto-assigned to `dev`).
        List<AuditLog> assignRows = rows.stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN)
                .toList();
        assertThat(assignRows).hasSize(3);
        assertThat(assignRows).allSatisfy(r -> {
            assertThat(r.getEntityType()).isEqualTo(EntityType.TICKET);
            assertThat(r.getActor()).isEqualTo(Actor.SYSTEM);
            assertThat(r.getPerformedBy()).isNull();
            assertThat(r.getDiff()).isEqualTo("{\"assigneeId\":" + dev.getId() + "}");
        });

        // The audit-row pairs share entity ids (one CREATE + one AUTO_ASSIGN per ticket).
        assertThat(createRows.stream().map(AuditLog::getEntityId).sorted().toList())
                .isEqualTo(assignRows.stream().map(AuditLog::getEntityId).sorted().toList());
    }

    // ---- spec §8 REQUIRES_NEW: per-row isolation across the wire ---------

    @Test
    @DisplayName("Spec 10 §8 + Session 11 D4: failing row rolls back ONLY itself; sibling rows + audit rows survive")
    void perRowIsolation_realTxBoundary() throws Exception {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate
                ,GoodA,a,,LOW,BUG,,
                ,FailRow,bad-status,GIBBERISH,LOW,BUG,,
                ,GoodB,b,,LOW,BUG,,
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].code").value("TICKET_INVALID_STATUS"));

        // Both successful tickets actually committed:
        List<Ticket> persisted = tickets.findByProjectId(project.getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(Ticket::getTitle)
                .containsExactlyInAnyOrder("GoodA", "GoodB");

        // Audit invariant: exactly two CREATE/USER rows + two AUTO_ASSIGN/
        // SYSTEM rows (slice 13 — the project owner `dev` is the lone
        // candidate and auto-assignment fires per row). The failing row's
        // REQUIRES_NEW tx rolled back BOTH its potential audit rows along
        // with the ticket itself — the failure is hermetic per spec 10 §8.
        List<AuditLog> all = auditLogs.findAll();
        assertThat(all).hasSize(4);
        assertThat(all.stream().filter(r -> r.getAction() == AuditAction.CREATE).toList())
                .hasSize(2);
        assertThat(all.stream().filter(r -> r.getAction() == AuditAction.AUTO_ASSIGN).toList())
                .hasSize(2);
    }

    // ---- spec §7 wrong MIME -----------------------------------------------

    @Test
    @DisplayName("Spec 10 §7: application/json content-type → 415 CSV_UNSUPPORTED_TYPE")
    void wrongMime_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.json", "application/json", "{}".getBytes());

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("CSV_UNSUPPORTED_TYPE"));

        assertThat(tickets.count()).isZero();
        assertThat(auditLogs.count()).isZero();
    }

    // ---- spec §"Unknown columns" — header diff ---------------------------

    @Test
    @DisplayName("Spec 10 §"
            + "Unknown columns"
            + ": unknown column → 400 CSV_UNKNOWN_COLUMN; nothing committed")
    void unknownColumn_returns400() throws Exception {
        String csv = """
                id,title,description,status,priority,type,assigneeId,dueDate,extra_col
                ,T,d,,LOW,BUG,,,whatever
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_UNKNOWN_COLUMN"))
                .andExpect(jsonPath("$.details[?(@.field=='extra_col')]").exists());

        assertThat(tickets.count()).isZero();
    }

    // ---- spec §3 empty result still returns header ------------------------

    @Test
    @DisplayName("Spec 10 §3: empty result still returns 200 with header-only CSV")
    void emptyExport_returnsHeaderOnly() throws Exception {
        // tickets.deleteAll() already ran in @BeforeEach; project has zero tickets.
        String body = export(project.getId());
        // Only the header line (commons-csv writes CRLF per RFC-4180).
        String[] lines = body.split("\r?\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).isEqualTo("id,title,description,status,priority,type,assigneeId,dueDate");
    }

    // ---- spec §1 soft-deleted filtered ------------------------------------

    @Test
    @DisplayName("Spec 10 §1: soft-deleted tickets are filtered from the export")
    void export_excludesSoftDeletedTickets() throws Exception {
        long aliveId = createTicket("Alive", "stays", "BUG", "LOW");
        long deadId = createTicket("Soon-Deleted", "goes away", "BUG", "LOW");
        // Soft-delete via the API (DELETE flips @SQLDelete to UPDATE deleted_at).
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/tickets/{id}", deadId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        String body = export(project.getId());
        String[] lines = body.split("\r?\n");
        assertThat(lines).hasSize(2); // header + 1 row
        assertThat(lines[1]).startsWith(aliveId + ",Alive,");
    }

    // ---- spec §2 content-disposition format ------------------------------

    @Test
    @DisplayName("Spec 10 §2: Content-Disposition filename uses tickets-project-<id>-<yyyyMMdd>.csv")
    void export_contentDispositionFilenameFormat() throws Exception {
        MvcResult result = mvc.perform(get("/tickets/export")
                        .param("projectId", String.valueOf(project.getId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern(
                                "attachment; filename=\"tickets-project-" + project.getId() + "-\\d{8}\\.csv\"")));
    }

    // ---- helpers -----------------------------------------------------------

    private long createTicket(String title, String desc, String type, String priority) throws Exception {
        String body = String.format(
                "{\"title\":\"%s\",\"description\":\"%s\",\"type\":\"%s\",\"priority\":\"%s\",\"projectId\":%d}",
                title, desc, type, priority, project.getId());
        MvcResult result = mvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String export(Long projectId) throws Exception {
        MvcResult started = mvc.perform(get("/tickets/export")
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult finished = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        return finished.getResponse().getContentAsString();
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
}

package com.att.tdp.issueflow.escalation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.att.tdp.issueflow.escalation.service.EscalationService;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import com.att.tdp.issueflow.users.domain.User;
import com.att.tdp.issueflow.users.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end proofs for slice 14 — properties only the full stack can
 * demonstrate:
 *
 * <ol>
 *   <li>The JPQL query actually filters by {@code status != DONE} +
 *       {@code dueDate < now} + (transparently, via slice-9
 *       {@code @SQLRestriction}) soft-deleted rows.</li>
 *   <li>The {@code AUTO_ESCALATE/SYSTEM} audit row is written through
 *       the real audit pipeline (slice 7's {@code AuditLogService}) and
 *       lands with the correct actor, no {@code performedBy}, and
 *       the spec §2 diff string.</li>
 *   <li>Spec §1 — manual PATCH of {@code priority} CLEARS
 *       {@code isOverdue} via the slice-5 D6 wiring (which was
 *       designed for exactly this contract); the NEXT escalation pass
 *       then re-evaluates from the new priority. This is the slice-5
 *       → slice-14 forward-compat handshake — validated here for the
 *       first time.</li>
 *   <li>Soft-deleted tickets are invisible to the escalation query
 *       (so a soft-deleted CRITICAL-overdue ticket gets no further
 *       audit rows, which would be confusing for ADMIN-only deleted-
 *       view consumers).</li>
 * </ol>
 *
 * <p>{@link Clock} is overridden to a fixed instant via a
 * {@link TestConfiguration} (D1 — Session 14). The fixed instant is
 * far in the future of every seeded {@code dueDate} so "overdue" is
 * deterministic across CI clocks / time zones.
 *
 * <p>Test wipe pattern follows the slice 13 / 11 idiom: not
 * {@code @Transactional}, manual wipe in {@code @BeforeEach} so audit
 * assertions see committed state. Logins go through {@code /auth/login}
 * which writes a {@code LOGIN/USER} row — cleared in
 * {@code auditLogs.deleteAll()} AFTER the login per the
 * .cursor/rules/30-testing.mdc Gotcha promoted in slice 13.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EscalationIntegrationTest.FixedClockConfig.class)
class EscalationIntegrationTest {

    /**
     * Pin time at a known instant strictly later than every seeded
     * {@code dueDate} below so the escalation query returns them
     * deterministically. Replaces the production {@link Clock} bean
     * for this test context only (Spring picks {@code @Primary} over
     * {@code @Bean} from ClockConfig).
     */
    @TestConfiguration
    static class FixedClockConfig {
        static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private ProjectRepository projects;
    @Autowired private TicketRepository tickets;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EscalationService escalation;

    @BeforeEach
    void wipe() {
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();
    }

    // ---- end-to-end priority bump + audit row ------------------------------

    @Test
    @DisplayName("Spec 13 §Algorithm + §2 — overdue LOW ticket gets bumped to MEDIUM through the real pipeline, with AUTO_ESCALATE/SYSTEM row")
    void overdueTicket_bumpsAndWritesAuditRow_endToEnd() {
        User dev = persistUser("esc_dev_1", Role.DEVELOPER);
        Project p = persistProject("esc-p-1", dev.getId());
        Ticket t = persistOverdueTicket(p.getId(), Priority.LOW);

        escalation.runEscalationPass();

        // Re-read from DB so we observe the committed update, not the
        // detached entity from persistOverdueTicket.
        Ticket reloaded = tickets.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(reloaded.isOverdue()).isFalse(); // bumps don't touch the flag

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.AUTO_ESCALATE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.TICKET);
        assertThat(row.getEntityId()).isEqualTo(t.getId());
        assertThat(row.getActor()).isEqualTo(Actor.SYSTEM);
        assertThat(row.getPerformedBy()).isNull();
        assertThat(row.getDiff()).isEqualTo("{\"from\":\"LOW\",\"to\":\"MEDIUM\"}");
    }

    // ---- DONE ticket excluded by query ------------------------------------

    @Test
    @DisplayName("Spec 13 §Algorithm point 1 — overdue DONE ticket is invisible to the query → no escalation, no audit row")
    void doneTicket_isInvisibleToQuery() {
        User dev = persistUser("esc_dev_done", Role.DEVELOPER);
        Project p = persistProject("esc-p-done", dev.getId());
        Ticket t = persistOverdueTicket(p.getId(), Priority.HIGH);
        t.setStatus(TicketStatus.DONE);
        tickets.save(t); // commit the DONE status

        escalation.runEscalationPass();

        Ticket reloaded = tickets.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getPriority()).isEqualTo(Priority.HIGH); // unchanged
        assertThat(auditLogs.findAll())
                .filteredOn(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .isEmpty();
    }

    // ---- soft-deleted ticket excluded -------------------------------------

    @Test
    @DisplayName("Spec 13 §Algorithm + slice 9 — soft-deleted overdue ticket is invisible (via @SQLRestriction) → no escalation row")
    void softDeletedTicket_isInvisible() {
        User dev = persistUser("esc_dev_sd", Role.DEVELOPER);
        Project p = persistProject("esc-p-sd", dev.getId());
        Ticket t = persistOverdueTicket(p.getId(), Priority.HIGH);

        // Soft-delete via the repo's deleteById (slice-9 @SQLDelete makes
        // this an UPDATE deleted_at = NOW() instead of a DELETE).
        tickets.deleteById(t.getId());

        escalation.runEscalationPass();

        // Audit log holds no AUTO_ESCALATE row for this ticket.
        assertThat(auditLogs.findAll())
                .filteredOn(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .isEmpty();
    }

    // ---- CRITICAL flip + idempotency through real pipeline -----------------

    @Test
    @DisplayName("Spec 13 §3 idempotency — first pass flips CRITICAL ticket's isOverdue (with row); second pass is a no-op (no row)")
    void criticalIdempotency_realPipeline() {
        User dev = persistUser("esc_dev_crit", Role.DEVELOPER);
        Project p = persistProject("esc-p-crit", dev.getId());
        Ticket t = persistOverdueTicket(p.getId(), Priority.CRITICAL);

        // Pass 1: flips isOverdue → row.
        escalation.runEscalationPass();
        Ticket afterPass1 = tickets.findById(t.getId()).orElseThrow();
        assertThat(afterPass1.isOverdue()).isTrue();
        assertThat(auditLogs.findAll())
                .filteredOn(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .hasSize(1);

        // Pass 2: nothing changes — idempotent skip per spec §3.
        escalation.runEscalationPass();
        Ticket afterPass2 = tickets.findById(t.getId()).orElseThrow();
        assertThat(afterPass2.isOverdue()).isTrue();
        // STILL exactly one AUTO_ESCALATE row — the second pass added none.
        assertThat(auditLogs.findAll())
                .filteredOn(r -> r.getAction() == AuditAction.AUTO_ESCALATE)
                .hasSize(1);
    }

    // ---- manual PATCH priority resets the cycle ---------------------------

    @Test
    @DisplayName("Spec 13 §1 + slice 5 D6 — manual PATCH priority resets isOverdue=false; next pass re-evaluates")
    void manualPatchPriority_resetsTheCycle() throws Exception {
        User dev = persistUser("esc_dev_reset", Role.DEVELOPER);
        Project p = persistProject("esc-p-reset", dev.getId());
        Ticket t = persistOverdueTicket(p.getId(), Priority.CRITICAL);
        String token = login("esc_dev_reset");
        auditLogs.deleteAll(); // drop LOGIN row + pre-existing rows

        // First pass: flips isOverdue=true.
        escalation.runEscalationPass();
        Ticket flagged = tickets.findById(t.getId()).orElseThrow();
        assertThat(flagged.isOverdue()).isTrue();

        // Manual PATCH downgrades to HIGH (priority change → slice-5 D6
        // sets isOverdue=false in the same UPDATE).
        String body = String.format(
                "{\"priority\":\"HIGH\",\"version\":%d}", flagged.getVersion());
        mvc.perform(patch("/tickets/{id}", t.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Ticket reset = tickets.findById(t.getId()).orElseThrow();
        assertThat(reset.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(reset.isOverdue()).isFalse(); // slice-5 D6 cleared it

        // Second escalation pass — ticket is STILL overdue (dueDate is in
        // the past), priority is now HIGH < CRITICAL → bumps to CRITICAL.
        escalation.runEscalationPass();
        Ticket reEscalated = tickets.findById(t.getId()).orElseThrow();
        assertThat(reEscalated.getPriority()).isEqualTo(Priority.CRITICAL);
        // Bump doesn't touch isOverdue — flag stays false for now.
        assertThat(reEscalated.isOverdue()).isFalse();

        // Audit log: at least one HIGH→CRITICAL escalation row from this
        // second pass. Earlier rows: original CRITICAL flip + the UPDATE
        // for the manual PATCH (auditLog row written by TicketService.update).
        List<AuditLog> escalateRows = auditLogs.findAll().stream()
                .filter(r -> r.getAction() == AuditAction.AUTO_ESCALATE).toList();
        // We deleted audits BETWEEN login and the first pass, so:
        // pass1 wrote 1 (flip), pass2 wrote 1 (HIGH→CRITICAL bump) = 2 total.
        assertThat(escalateRows).hasSize(2);
        assertThat(escalateRows.get(0).getDiff())
                .isEqualTo("{\"isOverdue\":{\"from\":false,\"to\":true}}");
        assertThat(escalateRows.get(1).getDiff())
                .isEqualTo("{\"from\":\"HIGH\",\"to\":\"CRITICAL\"}");
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

    /**
     * Persists a ticket with {@code dueDate} fixed in the past relative
     * to {@link FixedClockConfig#FIXED_NOW} — guaranteed to be returned
     * by the escalation query regardless of wall-clock.
     */
    private Ticket persistOverdueTicket(Long projectId, Priority priority) {
        Ticket t = new Ticket();
        t.setTitle("overdue-" + priority);
        t.setDescription("d");
        t.setProjectId(projectId);
        t.setStatus(TicketStatus.IN_PROGRESS);
        t.setPriority(priority);
        t.setType(TicketType.BUG);
        t.setOverdue(false);
        t.setDueDate(Instant.parse("2025-01-01T00:00:00Z")); // 5y before FIXED_NOW
        return tickets.save(t);
    }
}

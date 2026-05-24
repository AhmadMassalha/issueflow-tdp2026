package com.att.tdp.issueflow.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.attachments.repository.AttachmentRepository;
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
 * End-to-end proof for the cross-cutting properties of slice 12 that
 * the Mockito + WebMvc tests can't reach:
 *
 * <ol>
 *   <li>True byte-equality round-trip: bytes uploaded → bytes downloaded
 *       through real Hibernate {@code @Lob} + real multipart wire.</li>
 *   <li>Spec §6 audit invariant: ONE row per upload (CREATE/ATTACHMENT)
 *       + ONE row per delete (DELETE/ATTACHMENT), actor=USER, performedBy
 *       = real principal id.</li>
 *   <li>Session 12 D7 cascade: existing attachments survive a parent
 *       ticket's soft delete + remain downloadable by id; new uploads
 *       onto a soft-deleted parent return 404.</li>
 *   <li>Real MIME magic-byte mismatch surfaces 415 through the real
 *       filter chain + global handler.</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code AuditIntegrationTest} (Session 07 D9):
 * not {@code @Transactional}, manual wipe in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentsIntegrationTest {

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
    private AttachmentRepository attachments;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User dev;
    private Ticket ticket;
    private String token;

    @BeforeEach
    void wipe() throws Exception {
        attachments.deleteAll();
        tickets.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        auditLogs.deleteAll();

        dev = persistUser("att_dev", "att_dev@example.com", Role.DEVELOPER, "pw1234");
        Project p = persistProject("att-project", dev.getId());
        ticket = persistTicket(p.getId());
        token = login("att_dev", "pw1234");
        auditLogs.deleteAll();
    }

    // ---- byte-equality round-trip -----------------------------------------

    @Test
    @DisplayName("Upload PNG → download — bytes match exactly through real @Lob + multipart wire")
    void roundTrip_uploadDownloadByteEquality() throws Exception {
        byte[] payload = pngHeaderPlusBody("hello-world-bytes");

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", payload);

        MvcResult posted = mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(payload.length))
                .andReturn();

        long aid = json.readTree(posted.getResponse().getContentAsString()).get("id").asLong();

        MvcResult got = mvc.perform(get("/tickets/{tid}/attachments/{aid}", ticket.getId(), aid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Length", String.valueOf(payload.length)))
                .andReturn();

        byte[] downloaded = got.getResponse().getContentAsByteArray();
        assertThat(downloaded).containsExactly(payload);
    }

    // ---- spec §6 audit invariant ------------------------------------------

    @Test
    @DisplayName("Spec 11 §6: upload writes ONE CREATE/ATTACHMENT row, actor=USER, performedBy=dev.id")
    void upload_writesCreateAuditRow() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", pngHeaderPlusBody("a"));

        MvcResult posted = mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long aid = json.readTree(posted.getResponse().getContentAsString()).get("id").asLong();

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.ATTACHMENT);
        assertThat(row.getEntityId()).isEqualTo(aid);
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(dev.getId());
    }

    @Test
    @DisplayName("Spec 11 §6: delete writes ONE DELETE/ATTACHMENT row with entityId of the removed attachment")
    void delete_writesDeleteAuditRow() throws Exception {
        // Seed an attachment via the API so the post-creation audit row is real.
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", pngHeaderPlusBody("b"));

        MvcResult posted = mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long aid = json.readTree(posted.getResponse().getContentAsString()).get("id").asLong();
        auditLogs.deleteAll();

        mvc.perform(delete("/tickets/{tid}/attachments/{aid}", ticket.getId(), aid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        List<AuditLog> rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(rows.get(0).getEntityType()).isEqualTo(EntityType.ATTACHMENT);
        assertThat(rows.get(0).getEntityId()).isEqualTo(aid);
    }

    // ---- spec §1 — real magic-byte mismatch through full stack ------------

    @Test
    @DisplayName("Spec 11 §1: claiming image/png with non-PNG bytes → 415 ATTACHMENT_UNSUPPORTED_TYPE end-to-end")
    void upload_magicMismatch_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "not actually a png".getBytes());

        mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_UNSUPPORTED_TYPE"));

        assertThat(attachments.count()).isZero();
        assertThat(auditLogs.count()).isZero();
    }

    @Test
    @DisplayName("Spec 11 §1: text/plain skips magic sniff and uploads cleanly through real stack")
    void upload_textPlainHappy_e2e() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "some notes".getBytes());

        mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("text/plain"));

        assertThat(attachments.count()).isEqualTo(1);
    }

    // ---- Session 12 D7 — soft-delete interaction --------------------------

    @Test
    @DisplayName("Session 12 D7: soft-deleted ticket → new upload returns 404; existing attachments survive + remain downloadable")
    void softDeletedTicket_blocksNewUploadButPreservesExisting() throws Exception {
        byte[] payload = pngHeaderPlusBody("c");
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", payload);

        MvcResult posted = mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long aid = json.readTree(posted.getResponse().getContentAsString()).get("id").asLong();

        // Soft-delete the parent ticket.
        mvc.perform(delete("/tickets/{id}", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // New upload on the now-soft-deleted ticket → 404.
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "second.png", "image/png", pngHeaderPlusBody("d"));
        mvc.perform(multipart("/tickets/{tid}/attachments", ticket.getId())
                        .file(file2)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        // Existing attachment is STILL downloadable by id (no cascade per D7).
        MvcResult got = mvc.perform(get("/tickets/{tid}/attachments/{aid}", ticket.getId(), aid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(got.getResponse().getContentAsByteArray()).containsExactly(payload);
    }

    // ---- 404 on download with wrong attachment id -------------------------

    @Test
    @DisplayName("GET /tickets/{tid}/attachments/{aid} with non-existent aid → 404 ATTACHMENT_NOT_FOUND")
    void download_missingId_returns404() throws Exception {
        mvc.perform(get("/tickets/{tid}/attachments/{aid}", ticket.getId(), 99_999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Build a PNG payload: 8-byte signature + arbitrary body. Service's
     * magic-byte sniff only checks the prefix, so the body content is
     * irrelevant for validation but matters for byte-equality round-trip.
     */
    private static byte[] pngHeaderPlusBody(String body) {
        byte[] sig = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        byte[] bytes = body.getBytes();
        byte[] out = new byte[sig.length + bytes.length];
        System.arraycopy(sig, 0, out, 0, sig.length);
        System.arraycopy(bytes, 0, out, sig.length, bytes.length);
        return out;
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
}

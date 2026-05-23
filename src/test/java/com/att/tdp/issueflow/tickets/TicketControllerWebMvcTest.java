package com.att.tdp.issueflow.tickets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.enums.Priority;
import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.enums.TicketType;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.tickets.api.CreateTicketRequest;
import com.att.tdp.issueflow.tickets.api.PatchTicketRequest;
import com.att.tdp.issueflow.tickets.api.TicketController;
import com.att.tdp.issueflow.tickets.domain.Ticket;
import com.att.tdp.issueflow.tickets.service.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer coverage for {@code /tickets/**}.
 *
 * <p>Every acceptance criterion in {@code docs/spec/04-tickets.md} §1–§12 maps
 * to at least one named test, tagged in the {@code @DisplayName} with the
 * {@code "Spec 04 §N"} convention.
 *
 * <p>{@code addFilters = false} per the slice-3 Gotcha rule
 * ({@code .cursor/rules/30-testing.mdc}).
 */
@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class TicketControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private TicketService service;

    private static Ticket fixture(Long id, TicketStatus status, Long version) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setTitle("title");
        t.setDescription("desc");
        t.setStatus(status);
        t.setPriority(Priority.MEDIUM);
        t.setType(TicketType.FEATURE);
        t.setProjectId(1L);
        t.setAssigneeId(null);
        t.setOverdue(false);
        t.setVersion(version);
        t.setCreatedAt(Instant.parse("2026-05-23T10:00:00Z"));
        t.setUpdatedAt(Instant.parse("2026-05-23T10:00:00Z"));
        return t;
    }

    private static String createBody(String title) {
        return """
                {
                  "title": "%s",
                  "priority": "MEDIUM",
                  "type": "FEATURE",
                  "projectId": 1
                }
                """.formatted(title);
    }

    // ---- Spec 04 §1 — missing required field --------------------------------

    @Test
    @DisplayName("Spec 04 §1 — POST /tickets missing title → 400 VALIDATION_FAILED details[title]")
    void should_return400_whenTitleMissing() throws Exception {
        String body = """
                { "priority": "MEDIUM", "type": "FEATURE", "projectId": 1 }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("title")));
    }

    @Test
    @DisplayName("Spec 04 §1 — POST /tickets missing projectId → 400 VALIDATION_FAILED details[projectId]")
    void should_return400_whenProjectIdMissing() throws Exception {
        String body = """
                { "title": "hello", "priority": "MEDIUM", "type": "FEATURE" }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("projectId")));
    }

    // ---- Spec 04 §2 — invalid enum value ------------------------------------

    @Test
    @DisplayName("Spec 04 §2 — POST /tickets with bad priority → 400 TICKET_INVALID_PRIORITY details[priority]")
    void should_return400_whenPriorityUnknown() throws Exception {
        String body = """
                { "title": "x", "priority": "EXTREME", "type": "FEATURE", "projectId": 1 }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_PRIORITY"))
                .andExpect(jsonPath("$.details[*].field", hasItem("priority")));
    }

    @Test
    @DisplayName("Spec 04 §2 — POST /tickets with bad type → 400 TICKET_INVALID_TYPE details[type]")
    void should_return400_whenTypeUnknown() throws Exception {
        String body = """
                { "title": "x", "priority": "MEDIUM", "type": "EPIC", "projectId": 1 }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_TYPE"))
                .andExpect(jsonPath("$.details[*].field", hasItem("type")));
    }

    @Test
    @DisplayName("Spec 04 §2 — POST /tickets with bad status → 400 TICKET_INVALID_STATUS details[status]")
    void should_return400_whenStatusUnknown() throws Exception {
        String body = """
                {
                  "title": "x", "priority": "MEDIUM", "type": "FEATURE",
                  "projectId": 1, "status": "BLOCKED"
                }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_STATUS"))
                .andExpect(jsonPath("$.details[*].field", hasItem("status")));
    }

    // ---- Spec 04 §3 — project must exist ------------------------------------

    @Test
    @DisplayName("Spec 04 §3 — POST /tickets with missing project → 404 PROJECT_NOT_FOUND")
    void should_return404_whenProjectMissing() throws Exception {
        when(service.create(any(CreateTicketRequest.class)))
                .thenThrow(new NotFoundException(ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."));

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(createBody("x")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ---- Spec 04 §4 — invalid assignee --------------------------------------

    @Test
    @DisplayName("Spec 04 §4 — POST /tickets with non-DEVELOPER assignee → 422 INVALID_ASSIGNEE details[assigneeId]")
    void should_return422_whenAssigneeInvalid() throws Exception {
        when(service.create(any(CreateTicketRequest.class)))
                .thenThrow(new ValidationException(
                        ErrorCode.INVALID_ASSIGNEE,
                        "Assignee 7 is not a DEVELOPER (or does not exist).",
                        List.of(new ApiError.FieldIssue(
                                "assigneeId",
                                "must reference an existing user with role DEVELOPER"))));

        String body = """
                { "title": "x", "priority": "MEDIUM", "type": "FEATURE", "projectId": 1, "assigneeId": 7 }
                """;

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"))
                .andExpect(jsonPath("$.details[*].field", hasItem("assigneeId")));
    }

    // ---- Spec 04 §5 — response includes all fields --------------------------

    @Test
    @DisplayName("Spec 04 §5 — POST /tickets response includes isOverdue:false and version:0")
    void should_create_andIncludeIsOverdueAndVersion() throws Exception {
        when(service.create(any(CreateTicketRequest.class))).thenReturn(fixture(1L, TicketStatus.TODO, 0L));

        mvc.perform(post("/tickets").contentType(MediaType.APPLICATION_JSON).content(createBody("hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.isOverdue").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.type").value("FEATURE"));
    }

    // ---- Spec 04 §6 — version-required / version-stale ----------------------

    @Test
    @DisplayName("Spec 04 §6 — PATCH without 'version' → 400 VERSION_REQUIRED details[version]")
    void should_return400_whenVersionMissing() throws Exception {
        when(service.update(anyLong(), any(PatchTicketRequest.class)))
                .thenThrow(new ValidationException(
                        ErrorCode.VERSION_REQUIRED,
                        "version is required for PATCH /tickets/{id}.",
                        List.of(new ApiError.FieldIssue(
                                "version",
                                "must be the version returned by the most recent GET/POST/PATCH"))));

        String body = """
                { "title": "new" }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_REQUIRED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("version")));
    }

    @Test
    @DisplayName("Spec 04 §6 — PATCH with stale 'version' → 409 TICKET_VERSION_CONFLICT")
    void should_return409_whenVersionStale() throws Exception {
        when(service.update(anyLong(), any(PatchTicketRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.TICKET_VERSION_CONFLICT,
                        "Ticket 1 was modified by another transaction (yours: v4, server: v5)."));

        String body = """
                { "title": "new", "version": 4 }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VERSION_CONFLICT"));
    }

    // ---- Spec 04 §7 — FSM ---------------------------------------------------

    @Test
    @DisplayName("Spec 04 §7 — PATCH skip-step → 409 TICKET_INVALID_TRANSITION")
    void should_return409_whenInvalidTransition() throws Exception {
        when(service.update(anyLong(), any(PatchTicketRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.TICKET_INVALID_TRANSITION,
                        "Cannot transition ticket 1 from TODO to IN_REVIEW."));

        String body = """
                { "status": "IN_REVIEW", "version": 0 }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("Spec 04 §7 — PATCH happy one-step forward: TODO → IN_PROGRESS, response shape preserved")
    void should_patch_oneStepForward() throws Exception {
        Ticket updated = fixture(1L, TicketStatus.IN_PROGRESS, 1L);
        when(service.update(eq(1L), any(PatchTicketRequest.class))).thenReturn(updated);

        String body = """
                { "status": "IN_PROGRESS", "version": 0 }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---- Spec 04 §8 — DONE is immutable -------------------------------------

    @Test
    @DisplayName("Spec 04 §8 — PATCH on a DONE ticket → 409 TICKET_DONE_IS_IMMUTABLE")
    void should_return409_whenTicketDone() throws Exception {
        when(service.update(anyLong(), any(PatchTicketRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.TICKET_DONE_IS_IMMUTABLE,
                        "Ticket 1 is DONE and cannot be modified."));

        String body = """
                { "title": "tweak", "version": 5 }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_DONE_IS_IMMUTABLE"));
    }

    // ---- Spec 04 §10 — priority change clears isOverdue (visible via service mock) --

    @Test
    @DisplayName("Spec 04 §10 — PATCH priority change response shows isOverdue:false")
    void should_returnCleared_isOverdue_afterPriorityChange() throws Exception {
        Ticket updated = fixture(1L, TicketStatus.IN_PROGRESS, 2L);
        updated.setPriority(Priority.HIGH);
        updated.setOverdue(false);
        when(service.update(eq(1L), any(PatchTicketRequest.class))).thenReturn(updated);

        String body = """
                { "priority": "HIGH", "version": 1 }
                """;

        mvc.perform(patch("/tickets/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.isOverdue").value(false));
    }

    // ---- Spec 04 §11 — delete -----------------------------------------------

    @Test
    @DisplayName("Spec 04 §11 — DELETE /tickets/{id} → 204 on success (hard delete for slice 5)")
    void should_deleteTicket() throws Exception {
        doNothing().when(service).delete(1L);

        mvc.perform(delete("/tickets/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Spec 04 §11 — DELETE missing target → 404 TICKET_NOT_FOUND")
    void should_return404_whenDeleteTargetMissing() throws Exception {
        doThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "Ticket 99 was not found."))
                .when(service).delete(99L);

        mvc.perform(delete("/tickets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    // ---- Spec 04 §12 — list + missing projectId -----------------------------

    @Test
    @DisplayName("Spec 04 §12 — GET /tickets?projectId= happy: returns array filtered by project")
    void should_listByProjectId() throws Exception {
        when(service.findByProjectId(1L))
                .thenReturn(List.of(fixture(1L, TicketStatus.TODO, 0L),
                        fixture(2L, TicketStatus.IN_PROGRESS, 3L)));

        mvc.perform(get("/tickets").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("Spec 04 §12 — GET /tickets without 'projectId' → 400 MISSING_PARAMETER")
    void should_return400_whenProjectIdMissingOnList() throws Exception {
        mvc.perform(get("/tickets"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    // ---- generic missing-id 404 (cross-cutting per pattern from slice 4) ----

    @Test
    @DisplayName("GET /tickets/{id} missing → 404 TICKET_NOT_FOUND")
    void should_return404_whenTicketMissing() throws Exception {
        when(service.findById(99L))
                .thenThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "Ticket 99 was not found."));

        mvc.perform(get("/tickets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }
}

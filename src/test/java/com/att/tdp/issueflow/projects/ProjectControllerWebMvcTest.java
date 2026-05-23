package com.att.tdp.issueflow.projects;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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

import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.projects.api.CreateProjectRequest;
import com.att.tdp.issueflow.projects.api.PatchProjectRequest;
import com.att.tdp.issueflow.projects.api.ProjectController;
import com.att.tdp.issueflow.projects.domain.Project;
import com.att.tdp.issueflow.projects.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HTTP-layer coverage for {@code /projects/**}.
 *
 * <p>Each test maps to at least one acceptance criterion in
 * {@code docs/spec/03-projects.md} (referenced as "Spec 03 §N" in the
 * {@code @DisplayName}).
 *
 * <p>Filters are disabled ({@code addFilters = false}) per the slice-3 lesson
 * logged in {@code .cursor/rules/30-testing.mdc}: with Spring Security on the
 * classpath, the default chain would blanket-401 everything here. End-to-end
 * security behaviour for {@code /projects} can be added to
 * {@code SecurityIntegrationTest} in a later slice if needed; for now ADR 0006
 * specifies all 5 endpoints are simply "authenticated".
 *
 * <p>{@link GlobalExceptionHandler} is imported explicitly so the
 * {@code ApiError} envelope mapping is exercised here (slice-1 lesson).
 */
@WebMvcTest(controllers = ProjectController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ProjectControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ProjectService service;

    private static Project projectFixture(Long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("alpha");
        p.setDescription("a sample project");
        p.setOwnerId(1L);
        return p;
    }

    // ---- Spec 03 §1 — happy create + missing owner + missing name ----------

    @Test
    @DisplayName("POST /projects — 200 + flat response shape {id, name, description, ownerId}")
    void should_create_andReturnFlatShape() throws Exception {
        when(service.create(any(CreateProjectRequest.class))).thenReturn(projectFixture(1L));

        var body = json.writeValueAsString(new CreateProjectRequest("alpha", "a sample project", 1L));

        mvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("alpha"))
                .andExpect(jsonPath("$.description").value("a sample project"))
                .andExpect(jsonPath("$.ownerId").value(1))
                // README's response is flat — no expanded owner object, no timestamps.
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("POST /projects — Spec 03 §1: missing owner → 404 USER_NOT_FOUND")
    void should_return404UserNotFound_whenOwnerMissing() throws Exception {
        when(service.create(any(CreateProjectRequest.class)))
                .thenThrow(new NotFoundException(
                        ErrorCode.USER_NOT_FOUND, "User 99 was not found."));

        var body = json.writeValueAsString(new CreateProjectRequest("alpha", null, 99L));

        mvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/projects"));
    }

    @Test
    @DisplayName("POST /projects — Spec 03 §1: missing name → 400 VALIDATION_FAILED with details[name]")
    void should_return400_whenNameMissing() throws Exception {
        String body = """
                { "description": "x", "ownerId": 1 }
                """;

        mvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("name")));
    }

    @Test
    @DisplayName("POST /projects — Spec 03 §1: missing ownerId → 400 VALIDATION_FAILED with details[ownerId]")
    void should_return400_whenOwnerIdMissing() throws Exception {
        String body = """
                { "name": "alpha", "description": "x" }
                """;

        mvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("ownerId")));
    }

    // ---- Spec 03 §2 — duplicate (owner, name) ------------------------------

    @Test
    @DisplayName("POST /projects — Spec 03 §2: duplicate (owner, name) → 409 PROJECT_DUPLICATE_NAME")
    void should_return409_whenDuplicateForOwner() throws Exception {
        when(service.create(any(CreateProjectRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.PROJECT_DUPLICATE_NAME,
                        "Owner 1 already has a project named 'alpha'."));

        var body = json.writeValueAsString(new CreateProjectRequest("alpha", null, 1L));

        mvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409))
                .andExpect(jsonPath("$.code").value("PROJECT_DUPLICATE_NAME"));
    }

    // ---- Spec 03 §3 — list + fetch ----------------------------------------

    @Test
    @DisplayName("GET /projects — returns an array")
    void should_listAllProjects() throws Exception {
        Project p2 = projectFixture(2L);
        p2.setName("beta");
        when(service.findAll()).thenReturn(List.of(projectFixture(1L), p2));

        mvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].name").value("beta"));
    }

    @Test
    @DisplayName("GET /projects/{id} — returns the project when found")
    void should_getProjectById() throws Exception {
        when(service.findById(1L)).thenReturn(projectFixture(1L));

        mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerId").value(1));
    }

    // ---- Spec 03 §4 — PATCH semantics --------------------------------------

    @Test
    @DisplayName("PATCH /projects/{id} — Spec 03 §4: name-only update happy path")
    void should_patchNameOnly() throws Exception {
        Project updated = projectFixture(1L);
        updated.setName("alpha-renamed");
        when(service.update(eq(1L), any(PatchProjectRequest.class))).thenReturn(updated);

        var body = json.writeValueAsString(new PatchProjectRequest("alpha-renamed", null));

        mvc.perform(patch("/projects/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("alpha-renamed"))
                .andExpect(jsonPath("$.description").value("a sample project"));
    }

    @Test
    @DisplayName("PATCH /projects/{id} — Spec 03 §4: unknown fields (e.g. ownerId) are silently ignored")
    void should_silentlyIgnoreUnknownPatchFields() throws Exception {
        Project updated = projectFixture(1L);
        updated.setDescription("new desc");
        when(service.update(eq(1L), any(PatchProjectRequest.class))).thenReturn(updated);

        // Client sends ownerId, id, deletedAt — DTO doesn't declare them, so they vanish.
        String body = """
                {
                  "description": "new desc",
                  "ownerId": 999,
                  "id": 42,
                  "deletedAt": "2026-01-01T00:00:00Z"
                }
                """;

        mvc.perform(patch("/projects/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // Response ownerId is the fixture's original — extra field was discarded by Jackson.
                .andExpect(jsonPath("$.ownerId").value(1))
                .andExpect(jsonPath("$.ownerId", not(999)));
    }

    @Test
    @DisplayName("PATCH /projects/{id} — Spec 03 §4 / Session-04 D4: both fields absent → 400 VALIDATION_FAILED, details[_body]")
    void should_return400_whenBothFieldsAbsent() throws Exception {
        when(service.update(eq(1L), any(PatchProjectRequest.class)))
                .thenThrow(new ValidationException(
                        ErrorCode.VALIDATION_FAILED,
                        "At least one of 'name' or 'description' must be provided.",
                        List.of(new ApiError.FieldIssue(
                                "_body",
                                "at least one of name|description must be provided"))));

        String body = "{ }";

        mvc.perform(patch("/projects/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("_body")));
    }

    @Test
    @DisplayName("PATCH /projects/{id} — rename to a colliding name → 409 PROJECT_DUPLICATE_NAME")
    void should_return409_whenPatchRenameCollides() throws Exception {
        when(service.update(eq(1L), any(PatchProjectRequest.class)))
                .thenThrow(new ConflictException(
                        ErrorCode.PROJECT_DUPLICATE_NAME,
                        "Owner 1 already has a project named 'beta'."));

        var body = json.writeValueAsString(new PatchProjectRequest("beta", null));

        mvc.perform(patch("/projects/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_DUPLICATE_NAME"));
    }

    @Test
    @DisplayName("PATCH /projects/{id} — Spec 03 §6: target missing → 404 PROJECT_NOT_FOUND")
    void should_return404_whenPatchTargetMissing() throws Exception {
        when(service.update(anyLong(), any(PatchProjectRequest.class)))
                .thenThrow(new NotFoundException(
                        ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."));

        var body = json.writeValueAsString(new PatchProjectRequest("x", null));

        mvc.perform(patch("/projects/99").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ---- Spec 03 §5 — delete -----------------------------------------------

    @Test
    @DisplayName("DELETE /projects/{id} — 204 on success")
    void should_deleteProject() throws Exception {
        doNothing().when(service).delete(1L);

        mvc.perform(delete("/projects/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /projects/{id} — Spec 03 §6: target missing → 404 PROJECT_NOT_FOUND")
    void should_return404_whenDeleteTargetMissing() throws Exception {
        doThrow(new NotFoundException(
                ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."))
                .when(service).delete(99L);

        mvc.perform(delete("/projects/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    // ---- Spec 03 §6 — generic missing-id 404 ------------------------------

    @Test
    @DisplayName("GET /projects/{id} — Spec 03 §6: missing → 404 PROJECT_NOT_FOUND")
    void should_return404_whenProjectMissing() throws Exception {
        when(service.findById(99L))
                .thenThrow(new NotFoundException(
                        ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."));

        mvc.perform(get("/projects/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/projects/99"));
    }

    // ---- Slice 9: /deleted + /restore HTTP shape ---------------------------
    //
    // RBAC (403 for DEVELOPER, 200 for ADMIN) is covered in
    // SoftDeleteIntegrationTest, not here — @WebMvcTest with addFilters=false
    // bypasses @PreAuthorize entirely (slice-7 Gotcha). These tests just
    // verify the HTTP contract on the happy/error paths assuming the security
    // gate already passed.

    @Test
    @DisplayName("Slice 9: GET /projects/deleted returns array of soft-deleted projects with deletedAt populated")
    void should_listDeletedProjects() throws Exception {
        Project p = new Project();
        p.setId(99L);
        p.setName("dead");
        p.setDescription("d");
        p.setOwnerId(1L);
        p.setDeletedAt(java.time.Instant.parse("2026-05-24T00:00:00Z"));
        when(service.findAllDeleted()).thenReturn(List.of(p));

        mvc.perform(get("/projects/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].name").value("dead"))
                .andExpect(jsonPath("$[0].deletedAt").value("2026-05-24T00:00:00Z"));
    }

    @Test
    @DisplayName("Slice 9: POST /projects/{id}/restore returns 200 with the restored project")
    void should_restoreProject() throws Exception {
        Project p = new Project();
        p.setId(7L);
        p.setName("revived");
        p.setDescription("d");
        p.setOwnerId(1L);
        when(service.restore(7L)).thenReturn(p);

        mvc.perform(post("/projects/7/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("revived"))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    @Test
    @DisplayName("Slice 9: POST /projects/{id}/restore → 404 PROJECT_NOT_FOUND when row never existed")
    void should_return404_whenRestoreTargetMissing() throws Exception {
        when(service.restore(99L))
                .thenThrow(new NotFoundException(
                        ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."));

        mvc.perform(post("/projects/99/restore"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Slice 9: POST /projects/{id}/restore → 409 ALREADY_ACTIVE when project is already active")
    void should_return409_whenRestoreTargetAlreadyActive() throws Exception {
        when(service.restore(7L))
                .thenThrow(new ConflictException(
                        ErrorCode.ALREADY_ACTIVE, "Project 7 is already active."));

        mvc.perform(post("/projects/7/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ACTIVE"));
    }
}

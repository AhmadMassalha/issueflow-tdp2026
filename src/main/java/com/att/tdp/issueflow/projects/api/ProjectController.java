package com.att.tdp.issueflow.projects.api;

import com.att.tdp.issueflow.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for {@code /projects}.
 *
 * <p>Endpoint list per spec 03:
 * <ul>
 *   <li>{@code GET    /projects}              — list all</li>
 *   <li>{@code GET    /projects/{projectId}}  — fetch one</li>
 *   <li>{@code POST   /projects}              — create (200 OK; see below)</li>
 *   <li>{@code PATCH  /projects/{projectId}}  — partial update of name/description</li>
 *   <li>{@code DELETE /projects/{projectId}}  — hard delete (becomes soft-delete in slice 9)</li>
 * </ul>
 *
 * <p><b>Status-code divergence from REST convention:</b> {@code POST} returns
 * {@code 200 OK} (not {@code 201 Created}) to match the README example and to
 * stay consistent with the slice-2 {@code POST /users} call (Session-02 D3).
 *
 * <p><b>PATCH semantics (Session-04 D3/D4):</b>
 * <ul>
 *   <li>Omit a field (or send {@code null}) → that field stays unchanged.</li>
 *   <li>Send an empty string for {@code description} → clears the description.</li>
 *   <li>Send neither {@code name} nor {@code description} → {@code 400
 *       VALIDATION_FAILED} with a {@code _body}-scoped detail. Silent no-op
 *       was considered and rejected; explicit failures debug faster.</li>
 * </ul>
 *
 * <p><b>RBAC:</b> all five endpoints are open to any authenticated user per
 * ADR 0006. The default security chain (Slice 3 / {@code SecurityConfig})
 * already enforces "authenticated" globally; nothing extra needed here.
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;

    @GetMapping
    public List<ProjectResponse> list() {
        return service.findAll().stream().map(ProjectResponse::from).toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId) {
        return ProjectResponse.from(service.findById(projectId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK) // see class JavaDoc — README + slice-2 consistency
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return ProjectResponse.from(service.create(req));
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@PathVariable Long projectId,
                                  @Valid @RequestBody PatchProjectRequest req) {
        return ProjectResponse.from(service.update(projectId, req));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long projectId) {
        service.delete(projectId);
    }

    // ---- slice 9: soft-delete listing + restore (ADMIN-only) --------------

    /**
     * ADMIN-only listing of soft-deleted projects (spec 08 §4 + §5). 204 No
     * Content vs 204-with-empty-array vs 200-with-empty-array — we return 200
     * with an empty array for consistency with {@link #list()}.
     */
    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProjectResponse> listDeleted() {
        return service.findAllDeleted().stream().map(ProjectResponse::from).toList();
    }

    /**
     * ADMIN-only restore (spec 08 §6). 200 with the restored project on
     * success; 404 {@code PROJECT_NOT_FOUND} when the id was never used;
     * 409 {@code ALREADY_ACTIVE} when the project is already active.
     */
    @PostMapping("/{projectId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectResponse restore(@PathVariable Long projectId) {
        return ProjectResponse.from(service.restore(projectId));
    }
}

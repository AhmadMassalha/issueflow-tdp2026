package com.att.tdp.issueflow.assign.api;

import com.att.tdp.issueflow.assign.service.WorkloadService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workload read endpoint (spec 12 §Endpoint).
 *
 * <p>{@code GET /projects/{projectId}/workload} → {@code List<WorkloadEntry>}
 * sorted by {@code openTicketCount ASC, userId ASC}.
 *
 * <p><b>RBAC</b> ({@code @PreAuthorize("isAuthenticated()")}) — Session 13 D9:
 * spec is silent on roles for this endpoint; workload data is project-
 * scoped operational info, not sensitive. Consistent with ADR 0006
 * (open reads on {@code /projects}).
 *
 * <p><b>Not paginated</b> — Session 13 D5: per-project membership is bounded
 * by participation (usually small dozens of users), pagination would be
 * over-engineering and the spec doesn't ask for it. If a future
 * deployment needs it, wrap the {@code List} return in the existing
 * {@code PaginatedResponse} envelope (slice 10 D2) at the controller
 * layer without changing the service.
 *
 * <p>Lives in a top-level {@code @RestController} (mapped to
 * {@code /projects/{projectId}/workload}) rather than as a method on
 * {@code ProjectController}, so the slice-13 module owns all its own
 * URL surface. Same idiom as slice 8's
 * {@code TicketDependencyController}.
 */
@RestController
@RequestMapping("/projects/{projectId}/workload")
@RequiredArgsConstructor
public class WorkloadController {

    private final WorkloadService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<WorkloadEntry> get(@PathVariable Long projectId) {
        return service.forProject(projectId);
    }
}

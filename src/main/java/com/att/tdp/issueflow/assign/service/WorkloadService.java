package com.att.tdp.issueflow.assign.service;

import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only wrapper around the workload query for the
 * {@code GET /projects/{projectId}/workload} endpoint (spec 12 §Endpoint).
 *
 * <p>Two responsibilities only:
 * <ol>
 *   <li>Enforce {@code PROJECT_NOT_FOUND} (404) for missing or soft-deleted
 *       projects per spec §3. {@code ProjectRepository.existsById()}
 *       respects the {@code @SQLRestriction} on {@code Project} (slice 9),
 *       so a soft-deleted project returns 404 with the same code as a
 *       never-existed one — consistent with every other "project not
 *       found" path in the app.</li>
 *   <li>Delegate to {@link UserRepository#findWorkloadForProject(Long)}
 *       for the actual data.</li>
 * </ol>
 *
 * <p>NO sorting / filtering here — the SQL already returns
 * {@code (count ASC, userId ASC)} per spec §Endpoint. Doing it again in
 * Java would be wasted CPU AND introduce a drift risk if the spec ever
 * changes the order convention.
 *
 * <p>This service is intentionally thin — the algorithm itself lives in
 * {@link AutoAssigner}, which both this endpoint and ticket-create
 * routing share. Session 13 D6 (separate service) makes the read-side
 * endpoint trivial to implement and test.
 */
@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final UserRepository users;
    private final ProjectRepository projects;

    @Transactional(readOnly = true)
    public List<WorkloadEntry> forProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new NotFoundException(
                    ErrorCode.PROJECT_NOT_FOUND,
                    "Project " + projectId + " was not found.");
        }
        return users.findWorkloadForProject(projectId);
    }
}

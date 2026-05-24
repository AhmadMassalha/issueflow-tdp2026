package com.att.tdp.issueflow.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.assign.service.WorkloadService;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.projects.repository.ProjectRepository;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link WorkloadService}.
 *
 * <p>The service is intentionally thin (project-existence guard + delegate
 * to the repo), so the test surface is narrow: 404 on missing/soft-deleted
 * project, passthrough on happy. The sort + filter properties are owned
 * by the SQL — covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
class WorkloadServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private ProjectRepository projects;

    @InjectMocks
    private WorkloadService service;

    @Test
    @DisplayName("Spec 12 §3 — happy path: project exists → returns repo output verbatim, no reordering")
    void forProject_returnsRepoOutput() {
        when(projects.existsById(1L)).thenReturn(true);
        List<WorkloadEntry> stub = List.of(
                new WorkloadEntry(42L, "alice", 0L),
                new WorkloadEntry(7L,  "bob",   3L)
        );
        when(users.findWorkloadForProject(1L)).thenReturn(stub);

        List<WorkloadEntry> got = service.forProject(1L);

        // Passthrough — same reference, no Java-side sort.
        assertThat(got).isSameAs(stub);
    }

    @Test
    @DisplayName("Spec 12 §3 — project missing OR soft-deleted → 404 PROJECT_NOT_FOUND; repo never queried")
    void forProject_missingProject_returns404() {
        // ProjectRepository.existsById respects @SQLRestriction (slice 9), so a
        // soft-deleted project surfaces as 404 with the same code as a
        // never-existed one — matches the spec phrasing "if the project is
        // missing or soft-deleted."
        when(projects.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.forProject(99L))
                .isInstanceOf(NotFoundException.class)
                .extracting(t -> ((NotFoundException) t).code())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);

        verify(users, never()).findWorkloadForProject(any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}

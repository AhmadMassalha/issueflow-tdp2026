package com.att.tdp.issueflow.assign;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.assign.api.WorkloadController;
import com.att.tdp.issueflow.assign.api.WorkloadEntry;
import com.att.tdp.issueflow.assign.service.WorkloadService;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-shape coverage for {@link WorkloadController}: response envelope,
 * field names per spec verbatim, 404 path.
 *
 * <p>{@code addFilters = false} per slice-3 Gotcha — JWT chain is out of
 * scope for HTTP-shape tests; the integration test exercises the real
 * filter chain.
 */
@WebMvcTest(controllers = WorkloadController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class WorkloadControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WorkloadService service;

    @Test
    @DisplayName("Spec 12 §Endpoint — GET /projects/{id}/workload returns [{userId, username, openTicketCount}] verbatim")
    void get_returnsWorkloadList() throws Exception {
        when(service.forProject(1L)).thenReturn(List.of(
                new WorkloadEntry(42L, "alice", 0L),
                new WorkloadEntry(7L,  "bob",   3L)
        ));

        mvc.perform(get("/projects/{id}/workload", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Spec field names verbatim — userId (not "id"), openTicketCount (not "count").
                .andExpect(jsonPath("$[0].userId").value(42))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].openTicketCount").value(0))
                .andExpect(jsonPath("$[1].userId").value(7))
                .andExpect(jsonPath("$[1].username").value("bob"))
                .andExpect(jsonPath("$[1].openTicketCount").value(3));
    }

    @Test
    @DisplayName("Spec 12 §3 — project not found surfaces as 404 PROJECT_NOT_FOUND through GlobalExceptionHandler")
    void get_missingProject_returns404() throws Exception {
        when(service.forProject(99L))
                .thenThrow(new NotFoundException(
                        ErrorCode.PROJECT_NOT_FOUND, "Project 99 was not found."));

        mvc.perform(get("/projects/{id}/workload", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Empty workload (project exists, no members yet) — returns empty array (not null)")
    void get_emptyWorkload_returnsEmptyArray() throws Exception {
        when(service.forProject(1L)).thenReturn(List.of());

        mvc.perform(get("/projects/{id}/workload", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

package com.att.tdp.issueflow.dependencies;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.common.enums.TicketStatus;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.dependencies.api.AddDependencyRequest;
import com.att.tdp.issueflow.dependencies.api.DependencyController;
import com.att.tdp.issueflow.dependencies.domain.TicketDependency;
import com.att.tdp.issueflow.dependencies.repository.BlockerSummary;
import com.att.tdp.issueflow.dependencies.service.DependencyService;
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
 * HTTP-layer coverage for {@code /tickets/{ticketId}/dependencies/**}.
 *
 * <p>Per the slice-3 + slice-7 Gotchas: {@code addFilters = false} so the
 * security chain doesn't 401 us, and {@link GlobalExceptionHandler} is
 * imported so the error envelope mapping runs. The class doesn't use
 * {@code @AuthenticationPrincipal} so no SecurityContext seeding is
 * needed (controller methods take only the URL path + body, no
 * principal).
 */
@WebMvcTest(controllers = DependencyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DependencyControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private DependencyService service;

    private static TicketDependency dep(Long id, Long ticketId, Long blockerId) {
        TicketDependency d = new TicketDependency();
        d.setId(id);
        d.setTicketId(ticketId);
        d.setBlockerId(blockerId);
        return d;
    }

    // ---- POST: happy path ---------------------------------------------------

    @Test
    @DisplayName("POST: 201 with {id, ticketId, blockerId} envelope on success")
    void should_return201_andEnvelope() throws Exception {
        when(service.add(1L, 2L)).thenReturn(dep(99L, 1L, 2L));
        String body = json.writeValueAsString(new AddDependencyRequest(2L));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.ticketId").value(1))
                .andExpect(jsonPath("$.blockerId").value(2));
    }

    // ---- POST: each error code maps correctly through the global handler ---

    @Test
    @DisplayName("POST: DEPENDENCY_SELF → 422")
    void should_return422_onSelf() throws Exception {
        when(service.add(anyLong(), anyLong()))
                .thenThrow(new ValidationException(ErrorCode.DEPENDENCY_SELF, "self", List.of()));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(1L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_SELF"));
    }

    @Test
    @DisplayName("POST: DEPENDENCY_DIFFERENT_PROJECT → 422")
    void should_return422_onCrossProject() throws Exception {
        when(service.add(anyLong(), anyLong()))
                .thenThrow(new ValidationException(
                        ErrorCode.DEPENDENCY_DIFFERENT_PROJECT, "cross", List.of()));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(2L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_DIFFERENT_PROJECT"));
    }

    @Test
    @DisplayName("POST: DEPENDENCY_EXISTS → 409")
    void should_return409_onDuplicate() throws Exception {
        when(service.add(anyLong(), anyLong()))
                .thenThrow(new ConflictException(ErrorCode.DEPENDENCY_EXISTS, "dup"));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(2L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_EXISTS"));
    }

    @Test
    @DisplayName("POST: DEPENDENCY_CYCLE → 422")
    void should_return422_onCycle() throws Exception {
        when(service.add(anyLong(), anyLong()))
                .thenThrow(new ValidationException(
                        ErrorCode.DEPENDENCY_CYCLE, "cycle", List.of()));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(2L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"));
    }

    @Test
    @DisplayName("POST: TICKET_NOT_FOUND from service → 404")
    void should_return404_onMissingTicket() throws Exception {
        when(service.add(anyLong(), anyLong()))
                .thenThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "missing"));

        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(2L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    // ---- POST: DTO validation ----------------------------------------------

    @Test
    @DisplayName("POST: missing blockedBy → 400 VALIDATION_FAILED with field 'blockedBy'")
    void should_return400_whenBlockedByMissing() throws Exception {
        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[?(@.field == 'blockedBy')]").exists());
    }

    @Test
    @DisplayName("POST: blockedBy=0 (not positive) → 400 VALIDATION_FAILED")
    void should_return400_whenBlockedByNotPositive() throws Exception {
        mvc.perform(post("/tickets/{tid}/dependencies", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddDependencyRequest(0L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- GET: happy path ---------------------------------------------------

    @Test
    @DisplayName("GET: 200 with array of {id, title, status} (spec §5)")
    void should_return200_withBlockerArray() throws Exception {
        when(service.listBlockers(1L)).thenReturn(List.of(
                new BlockerSummary(10L, "alpha", TicketStatus.IN_PROGRESS),
                new BlockerSummary(11L, "beta", TicketStatus.DONE)));

        mvc.perform(get("/tickets/{tid}/dependencies", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].title").value("alpha"))
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[1].id").value(11))
                .andExpect(jsonPath("$[1].status").value("DONE"));
    }

    @Test
    @DisplayName("GET: 404 TICKET_NOT_FOUND when the ticket itself doesn't exist")
    void should_return404_whenTicketMissing() throws Exception {
        when(service.listBlockers(99L))
                .thenThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "missing"));

        mvc.perform(get("/tickets/{tid}/dependencies", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    // ---- DELETE -----------------------------------------------------------

    @Test
    @DisplayName("DELETE: 204 on success")
    void should_return204_onDelete() throws Exception {
        doNothing().when(service).remove(1L, 2L);

        mvc.perform(delete("/tickets/{tid}/dependencies/{bid}", 1L, 2L))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE: 404 DEPENDENCY_NOT_FOUND when row missing (tenancy idiom)")
    void should_return404_whenDeleteMissing() throws Exception {
        doThrow(new NotFoundException(ErrorCode.DEPENDENCY_NOT_FOUND, "not found"))
                .when(service).remove(1L, 2L);

        mvc.perform(delete("/tickets/{tid}/dependencies/{bid}", 1L, 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_NOT_FOUND"));
    }
}

package com.att.tdp.issueflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.audit.api.AuditLogController;
import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer coverage for {@code GET /audit-logs}.
 *
 * <p>Every spec 06 acceptance criterion (RBAC, filter validation, sort, page
 * envelope) maps to at least one named test. Uses the slice-3 Gotcha
 * ({@code addFilters = false}) so the security filter chain doesn't kick
 * in — but {@code @PreAuthorize("hasRole('ADMIN')")} is still enforced
 * because method security and filter security are independent. Tests seed
 * the {@code SecurityContext} with {@link WithMockUser} (for the simple
 * role-only paths) or imperatively (where assertions need the principal
 * id, which we don't here).
 */
/**
 * <p><b>403 branch coverage is in {@link AuditIntegrationTest}</b>, not here.
 * {@code @WebMvcTest} loads only the controller slice — the main
 * {@code SecurityConfig} (which carries {@code @EnableMethodSecurity}) is
 * NOT in scope, so {@code @PreAuthorize("hasRole('ADMIN')")} is a silent
 * no-op in this test class. Adding a custom {@code @EnableMethodSecurity}
 * config inside the test interferes with {@code @WebMvcTest}'s controller
 * registration (the AOP proxy seems to confuse the
 * {@code RequestMappingHandlerMapping} scan, yielding 404 on every URL).
 * The ADMIN/DEVELOPER role-gate is a thin Spring annotation; integration-
 * level tests with the real filter chain prove it works end-to-end.
 */
@WebMvcTest(controllers = AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuditLogControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditLogService service;

    private static AuditLog row(Long id, AuditAction action, EntityType entityType, Long entityId) {
        AuditLog r = new AuditLog();
        r.setId(id);
        r.setAction(action);
        r.setEntityType(entityType);
        r.setEntityId(entityId);
        r.setPerformedBy(10L);
        r.setActor(Actor.USER);
        r.setTimestamp(Instant.parse("2026-05-23T10:00:00Z"));
        return r;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- happy path ---------------------------------------------------------

    @Test
    @DisplayName("Spec 06 §4 — ADMIN: 200 with the standard PageResponse envelope (data/total/page/pageSize)")
    @WithMockUser(roles = "ADMIN")
    void should_return200_andEnvelope_forAdmin() throws Exception {
        Page<AuditLog> page = new PageImpl<>(
                List.of(row(7L, AuditAction.CREATE, EntityType.TICKET, 1L)),
                PageRequest.of(0, 20),
                1L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(page);

        // Session 10 D6: envelope is now {data, total, page, pageSize} with
        // 1-indexed wire page (Spring's 0 → wire's 1).
        mvc.perform(get("/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[0].entityType").value("TICKET"))
                .andExpect(jsonPath("$.data[0].entityId").value(1))
                .andExpect(jsonPath("$.data[0].actor").value("USER"))
                .andExpect(jsonPath("$.data[0].performedBy").value(10))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    // ---- RBAC: covered end-to-end in AuditIntegrationTest --------------------
    // (See class JavaDoc above for the @WebMvcTest + @EnableMethodSecurity
    //  incompatibility we hit. ADMIN/DEVELOPER role-gate is asserted with the
    //  real filter chain in AuditIntegrationTest.adminOnly_403ForDeveloper.)

    // ---- filter validation --------------------------------------------------

    @Test
    @DisplayName("Spec 06 §5 — unknown action enum: 400 VALIDATION_FAILED")
    @WithMockUser(roles = "ADMIN")
    void should_return400_forUnknownActionEnum() throws Exception {
        mvc.perform(get("/audit-logs").param("action", "DEFINITELY_NOT_AN_ACTION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Spec 06 §5 — unknown entityType enum: 400 VALIDATION_FAILED")
    @WithMockUser(roles = "ADMIN")
    void should_return400_forUnknownEntityTypeEnum() throws Exception {
        mvc.perform(get("/audit-logs").param("entityType", "BANANA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Spec 06 §5 — unknown actor enum: 400 VALIDATION_FAILED")
    @WithMockUser(roles = "ADMIN")
    void should_return400_forUnknownActorEnum() throws Exception {
        mvc.perform(get("/audit-logs").param("actor", "ALIEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- pagination --------------------------------------------------------

    @Test
    @DisplayName("Pagination — defaults: page=1 on wire (Spring 0), pageSize=20 when client omits both")
    @WithMockUser(roles = "ADMIN")
    void should_useDefaultPagination_whenOmitted() throws Exception {
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/audit-logs")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).find(eq(null), eq(null), eq(null), eq(null), captor.capture());
        // Spring side: page=1 - 1 = 0.
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("timestamp"))
                .isNotNull()
                .satisfies(o -> assertThat(o.isDescending()).isTrue());
    }

    @Test
    @DisplayName("Pagination — pageSize clamped to MAX_PAGE_SIZE=100 when client asks for more")
    @WithMockUser(roles = "ADMIN")
    void should_clampSize_whenOversize() throws Exception {
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/audit-logs").param("pageSize", "10000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).find(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Pagination — page < 1 on the wire clamps to Spring page 0 (forgiving, not 400)")
    @WithMockUser(roles = "ADMIN")
    void should_clampSubOnePage_toFirstPage() throws Exception {
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        // wire page=-5 → max(1, -5) - 1 = 0
        mvc.perform(get("/audit-logs").param("page", "-5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).find(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("Pagination — wire page=2 maps to Spring page=1 (1-indexed → 0-indexed)")
    @WithMockUser(roles = "ADMIN")
    void should_convert1IndexedPage_to0Indexed() throws Exception {
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(1, 20), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/audit-logs").param("page", "2"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).find(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
    }

    // ---- filter pass-through ------------------------------------------------

    @Test
    @DisplayName("Filters — all four supplied are passed through to the service unchanged")
    @WithMockUser(roles = "ADMIN")
    void should_passFiltersToService() throws Exception {
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/audit-logs")
                        .param("entityType", "TICKET")
                        .param("entityId", "42")
                        .param("action", "UPDATE")
                        .param("actor", "USER"))
                .andExpect(status().isOk());

        verify(service).find(
                eq(EntityType.TICKET),
                eq(42L),
                eq(AuditAction.UPDATE),
                eq(Actor.USER),
                any(Pageable.class));
    }

    // ---- imperatively-seeded principal (smoke-test the helper, not strictly needed) ---

    @Test
    @DisplayName("ADMIN principal in SecurityContext (without @WithMockUser): 200")
    void should_acceptPrincipalSeededInContext() throws Exception {
        IssueFlowUserPrincipal admin = new IssueFlowUserPrincipal(1L, "root", "h", Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        Page<AuditLog> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L);
        when(service.find(any(), any(), any(), any(), any(Pageable.class))).thenReturn(empty);

        mvc.perform(get("/audit-logs"))
                .andExpect(status().isOk());
    }
}

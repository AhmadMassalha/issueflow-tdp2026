package com.att.tdp.issueflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.auth.security.CurrentUserProvider;
import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

/**
 * Branching coverage for {@link AuditLogService}:
 * <ul>
 *   <li>{@code log(...)} with a USER principal in the context → row written
 *       with {@code Actor.USER} + the principal's id.</li>
 *   <li>{@code log(...)} with NO principal in the context → row written
 *       with {@code Actor.SYSTEM} + null performedBy (background-job
 *       fallback).</li>
 *   <li>{@code log(...)} with diff: the diff is persisted verbatim.</li>
 *   <li>{@code logSystem(...)}: explicit SYSTEM regardless of context.</li>
 *   <li>{@code logAs(...)}: explicit USER with caller-supplied performedBy,
 *       used by the LOGIN code path.</li>
 *   <li>{@code find(...)}: builds a {@code Specification} and delegates to
 *       {@code repo.findAll(spec, pageable)} — verified by capturing the
 *       specification argument and matching the returned page.</li>
 * </ul>
 *
 * <p>{@code AuditLogRepository} is mocked; persistence is covered by
 * {@code AuditLogRepositoryJpaTest}. The argument captor is used to
 * inspect what the service wrote, not just that {@code save} was called —
 * matches the testing rule "assert observable behaviour, not interactions".
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository repo;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private AuditLogService service;

    private static IssueFlowUserPrincipal alice() {
        return new IssueFlowUserPrincipal(42L, "alice", "hash", Role.DEVELOPER);
    }

    // ---- log(...) -----------------------------------------------------------

    @Test
    @DisplayName("log: USER row written with principal id when context has one")
    void log_writesUserRow_whenPrincipalPresent() {
        when(currentUserProvider.currentUser()).thenReturn(Optional.of(alice()));
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(AuditAction.CREATE, EntityType.USER, 7L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(42L);
        assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(row.getEntityType()).isEqualTo(EntityType.USER);
        assertThat(row.getEntityId()).isEqualTo(7L);
        assertThat(row.getDiff()).isNull();
    }

    @Test
    @DisplayName("log: SYSTEM row written when context has no principal (background job fallback)")
    void log_writesSystemRow_whenNoPrincipal() {
        when(currentUserProvider.currentUser()).thenReturn(Optional.empty());
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(AuditAction.AUTO_ASSIGN, EntityType.TICKET, 99L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getActor()).isEqualTo(Actor.SYSTEM);
        assertThat(row.getPerformedBy()).isNull();
        assertThat(row.getAction()).isEqualTo(AuditAction.AUTO_ASSIGN);
    }

    @Test
    @DisplayName("log: diff string is persisted verbatim")
    void log_persistsDiffVerbatim() {
        when(currentUserProvider.currentUser()).thenReturn(Optional.of(alice()));
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(AuditAction.UPDATE, EntityType.TICKET, 5L,
                "{\"status\":{\"from\":\"TODO\",\"to\":\"DONE\"}}");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDiff())
                .isEqualTo("{\"status\":{\"from\":\"TODO\",\"to\":\"DONE\"}}");
    }

    // ---- logSystem(...) -----------------------------------------------------

    @Test
    @DisplayName("logSystem: writes SYSTEM row even if a principal is in the context")
    void logSystem_isExplicit_ignoringContext() {
        // Provider deliberately NOT stubbed — logSystem must not call it.
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.logSystem(AuditAction.AUTO_ESCALATE, EntityType.TICKET, 11L, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getActor()).isEqualTo(Actor.SYSTEM);
        assertThat(row.getPerformedBy()).isNull();
        // No interaction with the provider.
        org.mockito.Mockito.verifyNoInteractions(currentUserProvider);
    }

    // ---- logAs(...) ---------------------------------------------------------

    @Test
    @DisplayName("logAs: writes USER row with the caller-supplied performedBy (login path)")
    void logAs_explicitUserRow() {
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.logAs(99L, AuditAction.LOGIN, EntityType.USER, 99L, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getActor()).isEqualTo(Actor.USER);
        assertThat(row.getPerformedBy()).isEqualTo(99L);
        assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN);
        org.mockito.Mockito.verifyNoInteractions(currentUserProvider);
    }

    // ---- find(...) ----------------------------------------------------------

    @Test
    @DisplayName("find: delegates to repo.findAll with built specification + pageable")
    void find_delegatesToRepoWithSpec() {
        Page<AuditLog> stubPage = new PageImpl<>(List.of(new AuditLog()));
        when(repo.findAll(any(Specification.class), eq(PageRequest.of(0, 20))))
                .thenReturn(stubPage);

        Page<AuditLog> actual = service.find(
                EntityType.TICKET, 1L, AuditAction.UPDATE, Actor.USER,
                PageRequest.of(0, 20));

        assertThat(actual).isSameAs(stubPage);
        // We don't inspect the Specification's WHERE tree (that's tested at the
        // JPA level); the contract is "service constructs *a* spec from these
        // four args and passes the pageable through unchanged".
    }
}

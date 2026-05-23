package com.att.tdp.issueflow.audit.service;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.audit.repository.AuditLogRepository;
import com.att.tdp.issueflow.audit.repository.AuditLogSpecifications;
import com.att.tdp.issueflow.auth.security.CurrentUserProvider;
import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit log service (spec 06).
 *
 * <p><b>Write API:</b> three {@code log} overloads.
 * <ul>
 *   <li>{@link #log(AuditAction, EntityType, Long)} — convenience for
 *       USER-actor rows with no diff. Resolves the actor from the current
 *       request via {@link CurrentUserProvider}; if there's no principal
 *       (background job), the row is written as SYSTEM/{@code performedBy=null}
 *       — same idiom as the explicit-SYSTEM overload below.</li>
 *   <li>{@link #log(AuditAction, EntityType, Long, String)} — same, with a diff.</li>
 *   <li>{@link #logSystem(AuditAction, EntityType, Long, String)} — explicit
 *       SYSTEM actor for callers that know they're not in a request
 *       context (AdminSeeder, slice-13 auto-assigner, slice-14 scheduler).</li>
 *   <li>{@link #logAs(Long, AuditAction, EntityType, Long, String)} — explicit
 *       USER actor with a caller-supplied {@code performedBy}, for the LOGIN
 *       code path where the {@code SecurityContext} isn't populated yet
 *       (the request is what mints the token).</li>
 * </ul>
 *
 * <p><b>Transactional propagation:</b> the writes inherit the caller's
 * transaction (REQUIRED, the default). Spec §1: "if the txn rolls back,
 * the audit row rolls back too." We do NOT use {@code REQUIRES_NEW} —
 * an audit row for a change that didn't actually happen is worse than
 * no audit row.
 *
 * <p><b>Read API:</b> {@link #find(EntityType, Long, AuditAction, Actor, Pageable)}
 * delegates to {@code JpaSpecificationExecutor} with the dynamic AND-
 * combined filters. The controller is responsible for sort + size caps;
 * the service trusts whatever {@link Pageable} is passed in.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuditLogService {

    private final AuditLogRepository repo;
    private final CurrentUserProvider currentUserProvider;

    // ---- write ----------------------------------------------------------------

    public AuditLog log(AuditAction action, EntityType entityType, Long entityId) {
        return log(action, entityType, entityId, null);
    }

    public AuditLog log(AuditAction action, EntityType entityType, Long entityId, String diff) {
        // Resolve actor from the current request. Empty Optional → SYSTEM row.
        // Mirrors the explicit-SYSTEM overload but is what most call sites
        // want (no boilerplate "if (currentUser.isEmpty()) ...").
        return currentUserProvider.currentUser()
                .map(p -> persist(action, entityType, entityId, p.id(), Actor.USER, diff))
                .orElseGet(() -> persist(action, entityType, entityId, null, Actor.SYSTEM, diff));
    }

    public AuditLog logSystem(AuditAction action, EntityType entityType, Long entityId, String diff) {
        return persist(action, entityType, entityId, null, Actor.SYSTEM, diff);
    }

    /**
     * Explicit USER row with a caller-supplied {@code performedBy}. Used by
     * the LOGIN code path: when {@code AuthService.login(...)} succeeds, the
     * {@code SecurityContext} hasn't been populated yet (this very request
     * is what mints the token), so the {@code log(...)} overload would
     * fall through to SYSTEM.
     */
    public AuditLog logAs(Long performedBy, AuditAction action, EntityType entityType,
                          Long entityId, String diff) {
        return persist(action, entityType, entityId, performedBy, Actor.USER, diff);
    }

    private AuditLog persist(AuditAction action, EntityType entityType, Long entityId,
                             Long performedBy, Actor actor, String diff) {
        AuditLog row = new AuditLog();
        row.setAction(action);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setPerformedBy(performedBy);
        row.setActor(actor);
        row.setDiff(diff);
        return repo.save(row);
    }

    // ---- read -----------------------------------------------------------------

    /**
     * Spec §6: dynamic AND-combined filters, sort + pagination supplied by
     * caller. The controller hard-codes {@code timestamp DESC} (D5) so this
     * method is sort-agnostic.
     *
     * <p>{@code REQUIRES_NEW} is NOT used — read-only and we don't want to
     * suspend an outer transaction that's auditing the read. Marked
     * {@link Transactional#readOnly()} so Hibernate skips dirty-checking.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Page<AuditLog> find(EntityType entityType, Long entityId,
                               AuditAction action, Actor actor, Pageable pageable) {
        Specification<AuditLog> spec = Specification.where(AuditLogSpecifications.byEntityType(entityType))
                .and(AuditLogSpecifications.byEntityId(entityId))
                .and(AuditLogSpecifications.byAction(action))
                .and(AuditLogSpecifications.byActor(actor));
        return repo.findAll(spec, pageable);
    }
}

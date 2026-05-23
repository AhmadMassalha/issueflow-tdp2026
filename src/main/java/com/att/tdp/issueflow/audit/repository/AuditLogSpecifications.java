package com.att.tdp.issueflow.audit.repository;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for {@code GET /audit-logs} (spec 06 §6).
 *
 * <p>Each method returns a {@link Specification} that is {@code null}-safe
 * — passing {@code null} produces a specification that matches every row,
 * letting the caller {@code .and(...)} them together without case-by-case
 * presence checks:
 *
 * <pre>{@code
 * Specification<AuditLog> spec = Specification.where(byEntityType(t))
 *     .and(byEntityId(id))
 *     .and(byAction(a))
 *     .and(byActor(actor));
 * }</pre>
 *
 * <p>Spec §6: filters are AND-combined. No OR / IN semantics are exposed —
 * if a reviewer wants "TICKET or COMMENT", they make two requests. Keeping
 * the query surface narrow makes the index strategy predictable.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    public static Specification<AuditLog> byEntityType(EntityType entityType) {
        return (root, query, cb) -> entityType == null
                ? cb.conjunction()
                : cb.equal(root.get("entityType"), entityType);
    }

    public static Specification<AuditLog> byEntityId(Long entityId) {
        return (root, query, cb) -> entityId == null
                ? cb.conjunction()
                : cb.equal(root.get("entityId"), entityId);
    }

    public static Specification<AuditLog> byAction(AuditAction action) {
        return (root, query, cb) -> action == null
                ? cb.conjunction()
                : cb.equal(root.get("action"), action);
    }

    public static Specification<AuditLog> byActor(Actor actor) {
        return (root, query, cb) -> actor == null
                ? cb.conjunction()
                : cb.equal(root.get("actor"), actor);
    }
}

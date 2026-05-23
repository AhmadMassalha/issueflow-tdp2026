package com.att.tdp.issueflow.audit.api;

import com.att.tdp.issueflow.audit.domain.AuditLog;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import java.time.Instant;

/**
 * JSON shape for a single audit-log row in the {@code GET /audit-logs}
 * response (always wrapped in a {@code PageResponse<AuditLogResponse>}).
 *
 * <p>{@code diff} is included verbatim — if it's null in the entity, the
 * field appears as JSON {@code null}. This matches the spec wording
 * ("nullable") and lets clients distinguish "no diff" from "empty diff".
 */
public record AuditLogResponse(
        Long id,
        AuditAction action,
        EntityType entityType,
        Long entityId,
        Long performedBy,
        Actor actor,
        String diff,
        Instant timestamp
) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getPerformedBy(),
                log.getActor(),
                log.getDiff(),
                log.getTimestamp()
        );
    }
}

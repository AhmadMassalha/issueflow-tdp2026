package com.att.tdp.issueflow.audit.api;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit log endpoint (spec 06 §6).
 *
 * <ul>
 *   <li>{@code GET /audit-logs?entityType=&entityId=&action=&actor=&page=&size=}</li>
 * </ul>
 *
 * <p><b>RBAC (spec §4):</b> ADMIN-only. {@code @PreAuthorize} bounces
 * non-ADMINs with 403 {@code AUTH_FORBIDDEN} via the global handler.
 *
 * <p><b>Filter validation (spec §5):</b> Spring binds each filter to its
 * typed {@code @RequestParam}; unknown enum values raise
 * {@link org.springframework.web.method.annotation.MethodArgumentTypeMismatchException}
 * which the existing {@code GlobalExceptionHandler.handleTypeMismatch}
 * arm (slice 1) turns into 400 {@code VALIDATION_FAILED}. No per-filter
 * code in this controller.
 *
 * <p><b>Sort (Session 07 D5):</b> hard-coded {@code timestamp DESC} —
 * spec §6 only sanctions descending-by-timestamp, and accepting a
 * client-supplied sort would let a misconfigured UI sort by id and
 * silently miss recent rows. Same idiom as slice-5 {@code GET /tickets}
 * not accepting arbitrary sort.
 *
 * <p><b>Pagination caps (Session 07 D5):</b> default size 20, max 100.
 * Clients asking for {@code size=10_000} get 100. Negative {@code page}
 * is clamped to 0. Bound checks live in {@link #pageable(int, int)}.
 */
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final AuditLogService service;

    @GetMapping
    public PageResponse<AuditLogResponse> list(
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Actor actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        Pageable pageable = pageable(page, size);
        return PageResponse.of(
                service.find(entityType, entityId, action, actor, pageable),
                AuditLogResponse::from);
    }

    private static Pageable pageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "timestamp"));
    }
}

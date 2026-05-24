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
 *   <li>{@code GET /audit-logs?entityType=&entityId=&action=&actor=&page=&pageSize=}</li>
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
 * <p><b>Pagination (Session 10 D6 + D8 — corrects slice-7 drift):</b>
 * wire is 1-indexed ({@code page=1} default) per
 * {@code .cursor/rules/20-api-contract.mdc}; param name is
 * {@code pageSize} (default 20, max 100, also from the rule). The
 * 1-indexed→0-indexed conversion lives in {@link #pageable(int, int)}.
 * Old clients sending {@code size=} or {@code page=0} now get a 400 via
 * unknown-param / out-of-range — documented as an explicit slice-10
 * correction in {@code prompts.md}.
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {

        Pageable pageable = pageable(page, pageSize);
        return PageResponse.of(
                service.find(entityType, entityId, action, actor, pageable),
                AuditLogResponse::from);
    }

    /**
     * Convert wire's 1-indexed {@code page} to Spring's 0-indexed. {@code
     * page < 1} clamps to page 1 (gives the first page rather than 400ing —
     * forgiving to misconfigured clients). {@code pageSize} clamps to
     * [1, 100].
     */
    private static Pageable pageable(int page, int pageSize) {
        int safePage = Math.max(page, 1) - 1; // 1-indexed wire → 0-indexed Spring
        int safeSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "timestamp"));
    }
}

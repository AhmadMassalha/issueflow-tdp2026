package com.att.tdp.issueflow.mentions.api;

import com.att.tdp.issueflow.comments.api.CommentResponse;
import com.att.tdp.issueflow.common.web.PageResponse;
import com.att.tdp.issueflow.mentions.service.MentionService;
import com.att.tdp.issueflow.mentions.service.MentionService.MentionResponseItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoint for the per-user mentions inbox (spec 09 §3).
 *
 * <ul>
 *   <li>{@code GET /users/{userId}/mentions?page=&pageSize=}</li>
 * </ul>
 *
 * <p><b>RBAC:</b> any authenticated user. No further role gate — the
 * mentions list is intentionally not privacy-sensitive ({@code "@alice"}
 * appearing on a comment is already visible to anyone who can read the
 * comment; aggregating those into one list per user adds no privilege).
 * If a real privacy concern surfaces later, a {@code @PreAuthorize
 * ("#userId == authentication.principal.id or hasRole('ADMIN')")} drop-in
 * here covers it.
 *
 * <p><b>Pagination:</b> standard {@link PageResponse} envelope
 * ({@code data/total/page/pageSize}, 1-indexed page) per
 * {@code .cursor/rules/20-api-contract.mdc} (Session 10 D6 corrected
 * slice-7's drift; this endpoint ships the corrected shape from day one).
 * Defaults: {@code page=1, pageSize=20}, max {@code pageSize=100}
 * (Session 10 D8).
 *
 * <p><b>Sort:</b> newest first by {@code mention.created_at}
 * (Session 10 D11 — when an old comment is edited to mention @me, the
 * fresh mention surfaces at the top with today's timestamp).
 *
 * <p><b>Out-of-range page:</b> returns empty {@code data[]} with the
 * correct {@code total} (spec 09 §5), not a 404. Same standard envelope
 * shape so clients can paginate confidently.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MentionController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final MentionService mentions;

    @GetMapping("/users/{userId}/mentions")
    public PageResponse<CommentResponse> list(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {

        Pageable pageable = pageable(page, pageSize);
        return PageResponse.of(
                mentions.findMentionsForUser(userId, pageable),
                MentionController::toResponse);
    }

    /**
     * 1-indexed wire {@code page} → 0-indexed Spring {@code Pageable}.
     * Tolerant clamping ({@code page < 1} → first page, {@code pageSize}
     * clamped to [1, 100]) — same idiom as {@code AuditLogController}.
     * Sort is implicit in the repository's named JPQL (newest first by
     * {@code mention.created_at}), so {@link Pageable} doesn't need a
     * {@code Sort} clause here.
     */
    private static Pageable pageable(int page, int pageSize) {
        int safePage = Math.max(page, 1) - 1;
        int safeSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        return PageRequest.of(safePage, safeSize);
    }

    private static CommentResponse toResponse(MentionResponseItem item) {
        return CommentResponse.from(item.comment(), item.mentionedUsers());
    }
}

package com.att.tdp.issueflow.comments.api;

import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.service.CommentService;
import com.att.tdp.issueflow.comments.service.CommentService.CommentWithMentions;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.service.MentionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for comments, nested under tickets per spec 05:
 * <ul>
 *   <li>{@code GET    /tickets/{ticketId}/comments}              — list, newest first</li>
 *   <li>{@code POST   /tickets/{ticketId}/comments}              — create (200 OK)</li>
 *   <li>{@code PATCH  /tickets/{ticketId}/comments/{commentId}}  — edit (own or ADMIN)</li>
 *   <li>{@code DELETE /tickets/{ticketId}/comments/{commentId}}  — delete (own or ADMIN), 204</li>
 * </ul>
 *
 * <p><b>Authentication:</b> every method requires an authenticated principal
 * (the global {@code SecurityConfig} chain already enforces this; the
 * {@code @PreAuthorize("isAuthenticated()")} class-level annotation is a
 * defensive belt-and-suspenders so a future revert of the global rule
 * won't silently open the endpoint set).
 *
 * <p><b>Author / RBAC:</b> {@code @AuthenticationPrincipal IssueFlowUserPrincipal}
 * is injected and threaded into the service. The author-or-admin RBAC check
 * lives in the service so it's pure-Java testable with Mockito (Session 06
 * D3 — see prompts.md for why we didn't use a SpEL bean callback).
 *
 * <p><b>POST returns 200 OK</b> (not 201) for consistency with every other
 * create in this codebase (Session 02 D3, /users; Session 04, /projects;
 * Session 05, /tickets). The README locks 200 for the create path on every
 * entity.
 */
@RestController
@RequestMapping("/tickets/{ticketId}/comments")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CommentController {

    private final CommentService service;
    private final MentionService mentions;

    /**
     * Spec 05 §2 list, now decorated with the slice-10 {@code mentionedUsers}
     * shape (Session 10 D7). Batched mention fetch keyed by comment id —
     * one round-trip for the whole page, regardless of size.
     */
    @GetMapping
    public List<CommentResponse> list(@PathVariable Long ticketId) {
        List<Comment> rows = service.listByTicket(ticketId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Comment::getId).toList();
        Map<Long, List<MentionedUserSummary>> mentionsByComment = mentions.findForComments(ids);
        return rows.stream()
                .map(c -> CommentResponse.from(c, mentionsByComment.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public CommentResponse create(@PathVariable Long ticketId,
                                  @Valid @RequestBody CreateCommentRequest req,
                                  @AuthenticationPrincipal IssueFlowUserPrincipal principal) {
        CommentWithMentions result = service.create(ticketId, req, principal);
        return CommentResponse.from(result.comment(), result.mentionedUsers());
    }

    @PatchMapping("/{commentId}")
    public CommentResponse update(@PathVariable Long ticketId,
                                  @PathVariable Long commentId,
                                  @Valid @RequestBody PatchCommentRequest req,
                                  @AuthenticationPrincipal IssueFlowUserPrincipal principal) {
        CommentWithMentions result = service.update(ticketId, commentId, req, principal);
        return CommentResponse.from(result.comment(), result.mentionedUsers());
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long ticketId,
                       @PathVariable Long commentId,
                       @AuthenticationPrincipal IssueFlowUserPrincipal principal) {
        service.delete(ticketId, commentId, principal);
    }
}

package com.att.tdp.issueflow.comments.service;

import com.att.tdp.issueflow.audit.service.AuditLogService;
import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.comments.api.CreateCommentRequest;
import com.att.tdp.issueflow.comments.api.PatchCommentRequest;
import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.ForbiddenException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.service.MentionService;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the {@code comments} feature.
 *
 * <p>Implements spec 05 §1–§6 verbatim. Cross-cutting design choices are
 * laid out in Session 06 of {@code prompts.md}; the most consequential are:
 *
 * <ul>
 *   <li><b>Author identity (D1):</b> {@code authorId} on create is ALWAYS
 *       the JWT principal's id, never trusted from the body. {@link
 *       CreateCommentRequest} doesn't even declare the field.</li>
 *   <li><b>URL tenancy (D2):</b> every PATCH/DELETE path queries
 *       {@code findByIdAndTicketId} — a comment requested via the wrong
 *       ticket URL returns 404 {@code COMMENT_NOT_FOUND}, indistinguishable
 *       from "comment does not exist".</li>
 *   <li><b>RBAC (D3):</b> {@code assertAuthorOrAdmin} runs AFTER the comment
 *       is loaded. Author-of-record check uses {@code authorId == principal.id()}
 *       (plain {@code Long} equality, not entity identity). Admins bypass.</li>
 *   <li><b>Optimistic locking (D4, ADR 0001):</b> two-layer enforcement
 *       (service pre-check + JPA flush-time fallback). The {@code Comment}
 *       arm of {@link com.att.tdp.issueflow.common.web.GlobalExceptionHandler#handleOptimisticLock}
 *       was pre-seeded in slice 5.</li>
 * </ul>
 *
 * <p><b>Why the {@code currentUser} param threads through:</b> the service
 * stays pure-Java and testable with Mockito alone — no
 * {@code SecurityContextHolder} mocking. The controller pulls
 * {@code @AuthenticationPrincipal IssueFlowUserPrincipal} once and passes
 * it explicitly. The same pattern will be reused by slice 7 (audit log)
 * which needs the actor on every state-changing call.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommentService {

    private final CommentRepository comments;
    private final TicketRepository tickets;
    private final AuditLogService auditLog;
    private final MentionService mentions;

    // ---- queries ------------------------------------------------------------

    /**
     * §2: list comments for a ticket, newest first.
     *
     * <p>The ticket-existence check is explicit so that listing comments on
     * a non-existent ticket returns 404 {@code TICKET_NOT_FOUND} rather than
     * an empty 200 — clients can distinguish "ticket exists but has no
     * comments" from "ticket doesn't exist". Once slice 9 lands,
     * {@code @SQLRestriction} on {@code Ticket} makes
     * {@code tickets.existsById} skip soft-deleted rows automatically, so
     * we don't need to filter the join result.
     */
    @Transactional(readOnly = true)
    public List<Comment> listByTicket(Long ticketId) {
        assertTicketExists(ticketId);
        return comments.findByTicketIdOrderByCreatedAtDesc(ticketId);
    }

    // ---- create -------------------------------------------------------------

    /**
     * §1: create a comment on a ticket.
     *
     * <p>{@code authorId} is taken from {@code currentUser.id()} (D1).
     * Any author field on the inbound DTO would be ignored — and is in fact
     * not declared on {@link CreateCommentRequest}.
     *
     * <p><b>Slice 10:</b> after the insert, {@link MentionService#syncForComment(Comment)}
     * runs in the same transaction (Session 10 D1) and returns the
     * resolved {@link MentionedUserSummary} list, which the controller
     * plugs into the response envelope. Empty content / no recognized
     * handles → empty list → no extra DB writes.
     */
    public CommentWithMentions create(Long ticketId, CreateCommentRequest req,
                                       IssueFlowUserPrincipal currentUser) {
        assertTicketExists(ticketId);

        Comment c = new Comment();
        c.setTicketId(ticketId);
        c.setAuthorId(currentUser.id());
        c.setContent(req.content());
        Comment saved = comments.save(c); // @Version starts at 0 on insert
        // Saved.getId() is non-null after save() (IDENTITY); we need it before syncing.
        List<MentionedUserSummary> mentioned = mentions.syncForComment(saved);
        auditLog.log(AuditAction.CREATE, EntityType.COMMENT, saved.getId());
        return new CommentWithMentions(saved, mentioned);
    }

    // ---- update -------------------------------------------------------------

    /**
     * §3 + §6: PATCH a comment.
     *
     * <p>Order of checks (each maps to a spec line / decision):
     * <ol>
     *   <li>{@code version == null} → 400 {@code VERSION_REQUIRED} (§3, D4).</li>
     *   <li>{@code findByIdAndTicketId} → 404 {@code COMMENT_NOT_FOUND} if
     *       missing OR wrong ticket (D2).</li>
     *   <li>{@code assertAuthorOrAdmin} → 403 {@code COMMENT_FORBIDDEN} if
     *       the caller is neither the author nor an ADMIN (§6, D3).</li>
     *   <li>{@code version} mismatch → 409 {@code COMMENT_VERSION_CONFLICT}
     *       (§3, fast-path). JPA flush-time race-window fallback emits the
     *       same code via the slice-5 handler arm.</li>
     *   <li>Apply {@code content} when non-null. Hibernate's
     *       dirty-checking persists on commit; {@code @Version} increments.</li>
     * </ol>
     */
    public CommentWithMentions update(Long ticketId, Long commentId, PatchCommentRequest req,
                                       IssueFlowUserPrincipal currentUser) {
        if (req.version() == null) {
            throw new ValidationException(
                    ErrorCode.VERSION_REQUIRED,
                    "version is required for PATCH /tickets/{ticketId}/comments/{commentId}.",
                    List.of(new ApiError.FieldIssue(
                            "version",
                            "must be the version returned by the most recent GET/POST/PATCH")));
        }

        Comment existing = loadOrThrow(commentId, ticketId);
        assertAuthorOrAdmin(existing, currentUser);

        if (!existing.getVersion().equals(req.version())) {
            throw new ConflictException(
                    ErrorCode.COMMENT_VERSION_CONFLICT,
                    "Comment " + commentId + " was modified by another transaction (yours: v"
                            + req.version() + ", server: v" + existing.getVersion() + ").");
        }

        boolean contentChanged = req.content() != null
                && !req.content().equals(existing.getContent());
        if (contentChanged) {
            existing.setContent(req.content());
        }

        // Re-sync mentions ONLY when content changed (Session 10 — same handles
        // for unchanged content = no-op work, plus avoids spurious DELETE/INSERT
        // churn that would invalidate mention.created_at). If content was
        // unchanged, hydrate the current mention list for the response.
        List<MentionedUserSummary> mentioned = contentChanged
                ? mentions.syncForComment(existing)
                : mentions.findForComment(commentId);

        auditLog.log(AuditAction.UPDATE, EntityType.COMMENT, commentId);
        return new CommentWithMentions(existing, mentioned);
    }

    // ---- delete -------------------------------------------------------------

    /**
     * §5: hard delete a comment. Same tenancy + RBAC checks as PATCH; no
     * version requirement (deletes are intent-driven and the spec is silent
     * on it).
     */
    public void delete(Long ticketId, Long commentId, IssueFlowUserPrincipal currentUser) {
        Comment existing = loadOrThrow(commentId, ticketId);
        assertAuthorOrAdmin(existing, currentUser);
        // Mention cleanup BEFORE the comment row goes — Session 10 D5.
        // Mentions have a non-cascading FK reference (plain Long); leaving them
        // behind would result in orphan rows pointing at a deleted comment id.
        mentions.deleteForComment(commentId);
        comments.delete(existing);
        auditLog.log(AuditAction.DELETE, EntityType.COMMENT, commentId);
    }

    // ---- helpers ------------------------------------------------------------

    private void assertTicketExists(Long ticketId) {
        if (!tickets.existsById(ticketId)) {
            throw new NotFoundException(
                    ErrorCode.TICKET_NOT_FOUND, "Ticket " + ticketId + " was not found.");
        }
    }

    private Comment loadOrThrow(Long commentId, Long ticketId) {
        return comments.findByIdAndTicketId(commentId, ticketId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.COMMENT_NOT_FOUND,
                        "Comment " + commentId + " was not found on ticket " + ticketId + "."));
    }

    /**
     * §6: edit/delete is allowed only when the caller authored the comment
     * OR has the ADMIN role. The 403 message intentionally does NOT reveal
     * the author's identity — we don't want a non-ADMIN to be able to probe
     * "who wrote comment N?" via the error envelope.
     */
    private void assertAuthorOrAdmin(Comment comment, IssueFlowUserPrincipal currentUser) {
        boolean isAdmin = currentUser.role() == Role.ADMIN;
        boolean isAuthor = comment.getAuthorId().equals(currentUser.id());
        if (!isAdmin && !isAuthor) {
            throw new ForbiddenException(
                    ErrorCode.COMMENT_FORBIDDEN,
                    "You can only edit or delete your own comments.");
        }
    }

    /**
     * Service-layer return tuple for create + update — the Comment plus its
     * post-sync mention list. The controller maps this to
     * {@code CommentResponse.from(comment, mentionedUsers)} (Session 10 D7).
     * Keeping these together at the service boundary means the controller
     * never has to invoke {@code MentionService} explicitly to fill the
     * response shape — the transaction has already done it.
     */
    public record CommentWithMentions(Comment comment, List<MentionedUserSummary> mentionedUsers) {
    }
}

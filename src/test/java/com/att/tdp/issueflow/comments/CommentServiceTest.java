package com.att.tdp.issueflow.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.comments.api.CreateCommentRequest;
import com.att.tdp.issueflow.comments.api.PatchCommentRequest;
import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.comments.service.CommentService;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.ForbiddenException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.tickets.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Branching-logic tests for {@link CommentService}.
 *
 * <p>Spec 05 §1–§6 mapped one-to-one with named tests below. Decision
 * branches from Session 06 (D1 author-from-JWT, D2 tenancy 404, D3 RBAC,
 * D4 version-required, D6 list order delegation) all asserted.
 *
 * <p>The author / tenancy split lets us isolate three otherwise-overlapping
 * 404 paths: ticket missing, comment missing, comment-on-wrong-ticket.
 * All three end up as 404 in the response, but they go through different
 * code paths in the service — covering each separately catches "I deleted
 * the wrong check" regressions.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository comments;

    @Mock
    private TicketRepository tickets;

    @InjectMocks
    private CommentService service;

    // ---- fixtures -----------------------------------------------------------

    private static final Long TICKET_ID = 1L;
    private static final Long OTHER_TICKET_ID = 2L;
    private static final Long COMMENT_ID = 100L;
    private static final Long AUTHOR_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    private static IssueFlowUserPrincipal principal(Long id, Role role) {
        return new IssueFlowUserPrincipal(id, "u" + id, "hash", role);
    }

    private static Comment existing(Long id, Long ticketId, Long authorId, Long version) {
        Comment c = new Comment();
        c.setId(id);
        c.setTicketId(ticketId);
        c.setAuthorId(authorId);
        c.setContent("original");
        c.setVersion(version);
        return c;
    }

    // ---- Spec 05 §2 — list ----------------------------------------------------

    @Test
    @DisplayName("Spec 05 §2: list delegates to derived query (newest first); 404 TICKET_NOT_FOUND when ticket missing")
    void listByTicket_happyAndMissingTicket() {
        when(tickets.existsById(TICKET_ID)).thenReturn(true);
        Comment c1 = existing(101L, TICKET_ID, AUTHOR_ID, 0L);
        Comment c2 = existing(102L, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByTicketIdOrderByCreatedAtDesc(TICKET_ID)).thenReturn(List.of(c2, c1));

        assertThat(service.listByTicket(TICKET_ID))
                .extracting(Comment::getId).containsExactly(102L, 101L);

        when(tickets.existsById(OTHER_TICKET_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.listByTicket(OTHER_TICKET_ID))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    // ---- Spec 05 §1 — create --------------------------------------------------

    @Test
    @DisplayName("Spec 05 §1 + Session 06 D1: create derives authorId from JWT principal, not from the body")
    void create_setsAuthorFromPrincipal_andSavesWithTicketId() {
        when(tickets.existsById(TICKET_ID)).thenReturn(true);
        when(comments.save(any(Comment.class))).thenAnswer(i -> {
            Comment c = i.getArgument(0);
            c.setId(COMMENT_ID);
            c.setVersion(0L);
            return c;
        });

        Comment result = service.create(
                TICKET_ID,
                new CreateCommentRequest("hello"),
                principal(AUTHOR_ID, Role.DEVELOPER));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(captor.capture());
        assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(captor.getValue().getTicketId()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().getContent()).isEqualTo("hello");
        assertThat(result.getId()).isEqualTo(COMMENT_ID);
    }

    @Test
    @DisplayName("Spec 05 §1: create on missing ticket → 404 TICKET_NOT_FOUND (no save)")
    void create_ticketMissing() {
        when(tickets.existsById(TICKET_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                TICKET_ID,
                new CreateCommentRequest("hello"),
                principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.TICKET_NOT_FOUND);

        verify(comments, never()).save(any());
    }

    // ---- Spec 05 §3 — version-required + version-conflict ---------------------

    @Test
    @DisplayName("Spec 05 §3 + Session 06 D4: PATCH without version → 400 VERSION_REQUIRED (no lookup)")
    void update_missingVersion() {
        assertThatThrownBy(() -> service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("new", null),
                principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(ValidationException.class)
                .extracting("code").isEqualTo(ErrorCode.VERSION_REQUIRED);

        verify(comments, never()).findByIdAndTicketId(any(), any());
    }

    @Test
    @DisplayName("Spec 05 §3: PATCH with stale version → 409 COMMENT_VERSION_CONFLICT (fast-path pre-check)")
    void update_staleVersion() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 1L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        assertThatThrownBy(() -> service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("new", 0L),
                principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_VERSION_CONFLICT);

        assertThat(loaded.getContent()).isEqualTo("original"); // unchanged
    }

    // ---- Session 06 D2 — URL tenancy ------------------------------------------

    @Test
    @DisplayName("Session 06 D2: PATCH on comment that exists but belongs to a different ticket → 404 COMMENT_NOT_FOUND")
    void update_commentBelongsToOtherTicket() {
        // The repository query already encodes the tenancy invariant; the
        // service simply trusts an empty Optional.
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("new", 0L),
                principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("Session 06 D2: DELETE on comment that exists but belongs to a different ticket → 404 COMMENT_NOT_FOUND")
    void delete_commentBelongsToOtherTicket() {
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(
                TICKET_ID, COMMENT_ID, principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_NOT_FOUND);

        verify(comments, never()).delete(any());
    }

    // ---- Spec 05 §6 — RBAC (own OR admin) -------------------------------------

    @Test
    @DisplayName("Spec 05 §6: PATCH by another non-admin user → 403 COMMENT_FORBIDDEN (no version check reached)")
    void update_otherUserNonAdmin_forbidden() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        assertThatThrownBy(() -> service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("hacked", 0L),
                principal(OTHER_USER_ID, Role.DEVELOPER)))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_FORBIDDEN);
    }

    @Test
    @DisplayName("Spec 05 §6: PATCH by ADMIN on another user's comment → succeeds")
    void update_adminCanEditOthers() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        Comment result = service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("moderated", 0L),
                principal(OTHER_USER_ID, Role.ADMIN));

        assertThat(result.getContent()).isEqualTo("moderated");
    }

    @Test
    @DisplayName("Spec 05 §6: PATCH by author → succeeds, version-controlled update")
    void update_authorEditsOwn() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        Comment result = service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest("edited", 0L),
                principal(AUTHOR_ID, Role.DEVELOPER));

        assertThat(result.getContent()).isEqualTo("edited");
        // No explicit save() — dirty-checking persists at commit. Verify
        // that no UPDATE happens through an explicit save call so we don't
        // accidentally double-save (which would mask version issues).
        verify(comments, never()).save(any());
    }

    @Test
    @DisplayName("PATCH with null content but valid version → version-controlled no-op (intentional, see PatchCommentRequest JavaDoc)")
    void update_nullContentIsNoop_butRunsVersionCheck() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 3L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        Comment result = service.update(
                TICKET_ID, COMMENT_ID,
                new PatchCommentRequest(null, 3L),
                principal(AUTHOR_ID, Role.DEVELOPER));

        assertThat(result.getContent()).isEqualTo("original");
    }

    // ---- Spec 05 §5 — delete --------------------------------------------------

    @Test
    @DisplayName("Spec 05 §5: DELETE by author → succeeds (hard delete)")
    void delete_authorOk() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        service.delete(TICKET_ID, COMMENT_ID, principal(AUTHOR_ID, Role.DEVELOPER));

        verify(comments).delete(loaded);
    }

    @Test
    @DisplayName("Spec 05 §5: DELETE by ADMIN on another user's comment → succeeds")
    void delete_adminOk() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        service.delete(TICKET_ID, COMMENT_ID, principal(OTHER_USER_ID, Role.ADMIN));

        verify(comments).delete(loaded);
    }

    @Test
    @DisplayName("Spec 05 §6: DELETE by another non-admin user → 403 COMMENT_FORBIDDEN (no delete)")
    void delete_otherUserNonAdmin_forbidden() {
        Comment loaded = existing(COMMENT_ID, TICKET_ID, AUTHOR_ID, 0L);
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.of(loaded));

        assertThatThrownBy(() -> service.delete(
                TICKET_ID, COMMENT_ID, principal(OTHER_USER_ID, Role.DEVELOPER)))
                .isInstanceOf(ForbiddenException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_FORBIDDEN);

        verify(comments, never()).delete(any());
    }

    @Test
    @DisplayName("Spec 05 §5: DELETE on missing comment → 404 COMMENT_NOT_FOUND (no delete)")
    void delete_missing() {
        when(comments.findByIdAndTicketId(COMMENT_ID, TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(
                TICKET_ID, COMMENT_ID, principal(AUTHOR_ID, Role.DEVELOPER)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.COMMENT_NOT_FOUND);

        verify(comments, never()).delete(any());
    }
}

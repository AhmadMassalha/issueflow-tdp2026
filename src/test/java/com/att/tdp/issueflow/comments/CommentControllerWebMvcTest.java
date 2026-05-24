package com.att.tdp.issueflow.comments;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.auth.security.IssueFlowUserPrincipal;
import com.att.tdp.issueflow.comments.api.CommentController;
import com.att.tdp.issueflow.comments.api.CreateCommentRequest;
import com.att.tdp.issueflow.comments.api.PatchCommentRequest;
import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.service.CommentService;
import com.att.tdp.issueflow.comments.service.CommentService.CommentWithMentions;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.service.MentionService;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.ForbiddenException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.exception.ValidationException;
import com.att.tdp.issueflow.common.web.ApiError;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-layer coverage for {@code /tickets/{ticketId}/comments/**}.
 *
 * <p>Every acceptance criterion in {@code docs/spec/05-comments.md} §1–§6
 * maps to at least one named test, tagged in the {@code @DisplayName} with
 * the {@code "Spec 05 §N"} convention.
 *
 * <p>{@code addFilters = false} per the slice-3 Gotcha rule
 * ({@code .cursor/rules/30-testing.mdc}) — the security filter chain is
 * out of scope for {@code @WebMvcTest}. We still need a principal in the
 * security context so {@code @AuthenticationPrincipal} resolves correctly;
 * the {@link #as(IssueFlowUserPrincipal)} helper below sets one
 * imperatively via a {@link RequestPostProcessor}. {@link #clearSecurityContext()}
 * resets it after each test so cross-test pollution can't mask bugs.
 *
 * <p>The pattern is new in this slice — first time we need the principal
 * resolver path inside a {@code @WebMvcTest}. If it recurs (slice 7, audit
 * log), promote {@code as(...)} into a shared test-support package.
 */
@WebMvcTest(controllers = CommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CommentControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CommentService service;

    /**
     * Slice 10 wiring: the controller batches mention lookups for the list
     * endpoint and uses the service's {@code CommentWithMentions} tuple for
     * create/update. Mock with {@code List.of()} when the test doesn't care
     * about mention content; set richer maps when it does.
     */
    @MockitoBean
    private MentionService mentions;

    private static final Long TICKET_ID = 1L;
    private static final Long COMMENT_ID = 100L;
    private static final Long AUTHOR_ID = 10L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static IssueFlowUserPrincipal principal(Long id, Role role) {
        return new IssueFlowUserPrincipal(id, "u" + id, "hash", role);
    }

    /** Imperatively seed the SecurityContext so @AuthenticationPrincipal resolves. */
    private static RequestPostProcessor as(IssueFlowUserPrincipal p) {
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
            return request;
        };
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Comment fixture(Long id, Long ticketId, Long authorId, String content, Long version) {
        Comment c = new Comment();
        c.setId(id);
        c.setTicketId(ticketId);
        c.setAuthorId(authorId);
        c.setContent(content);
        c.setVersion(version);
        // BaseEntity timestamps — reflection-free workaround: rely on getters
        // returning null in tests is fine; the response then serialises them
        // as JSON null. Where we want deterministic timestamps, set via
        // package-private setters that already live on BaseEntity. We just
        // assert presence/shape, not the exact timestamp.
        return c;
    }

    // ---- Spec 05 §1 — create --------------------------------------------------

    @Test
    @DisplayName("Spec 05 §1 + Session 06 D5: POST returns 200 with the saved comment and mentionedUsers:[]")
    void create_returns200_withMentionedUsersEmpty() throws Exception {
        when(service.create(eq(TICKET_ID), any(CreateCommentRequest.class), any(IssueFlowUserPrincipal.class)))
                .thenReturn(new CommentWithMentions(
                        fixture(COMMENT_ID, TICKET_ID, AUTHOR_ID, "hi", 0L),
                        List.of()));

        mvc.perform(post("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCommentRequest("hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMMENT_ID))
                .andExpect(jsonPath("$.ticketId").value(TICKET_ID))
                .andExpect(jsonPath("$.authorId").value(AUTHOR_ID))
                .andExpect(jsonPath("$.content").value("hi"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.mentionedUsers", hasSize(0)));
    }

    @Test
    @DisplayName("Session 10 D7: POST returns mentionedUsers as [{id, username, fullName}] when the comment has @-mentions")
    void create_returnsRichMentionedUsers_whenPresent() throws Exception {
        var alice = new MentionedUserSummary(42L, "alice", "Alice Liddell");
        var bob = new MentionedUserSummary(43L, "bob", "Bob Roberts");
        when(service.create(eq(TICKET_ID), any(CreateCommentRequest.class), any(IssueFlowUserPrincipal.class)))
                .thenReturn(new CommentWithMentions(
                        fixture(COMMENT_ID, TICKET_ID, AUTHOR_ID, "hi @alice @bob", 0L),
                        List.of(alice, bob)));

        mvc.perform(post("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCommentRequest("hi @alice @bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mentionedUsers", hasSize(2)))
                .andExpect(jsonPath("$.mentionedUsers[0].id").value(42))
                .andExpect(jsonPath("$.mentionedUsers[0].username").value("alice"))
                .andExpect(jsonPath("$.mentionedUsers[0].fullName").value("Alice Liddell"))
                .andExpect(jsonPath("$.mentionedUsers[1].id").value(43))
                .andExpect(jsonPath("$.mentionedUsers[1].username").value("bob"))
                .andExpect(jsonPath("$.mentionedUsers[1].fullName").value("Bob Roberts"));
    }

    @Test
    @DisplayName("Spec 05 §1: POST with blank content → 400 VALIDATION_FAILED on `content`")
    void create_blankContent_400() throws Exception {
        mvc.perform(post("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.details[0].field").value("content"));
    }

    @Test
    @DisplayName("Spec 05 §1: POST with content > 5000 chars → 400 VALIDATION_FAILED on `content`")
    void create_oversizeContent_400() throws Exception {
        String tooBig = "x".repeat(5001);
        mvc.perform(post("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCommentRequest(tooBig))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.details[0].field").value("content"));
    }

    @Test
    @DisplayName("Spec 05 §1: POST on missing ticket → 404 TICKET_NOT_FOUND")
    void create_missingTicket_404() throws Exception {
        when(service.create(eq(TICKET_ID), any(), any()))
                .thenThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "Ticket 1 was not found."));

        mvc.perform(post("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCommentRequest("hi"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.TICKET_NOT_FOUND.name()));
    }

    // ---- Spec 05 §2 — list ----------------------------------------------------

    @Test
    @DisplayName("Spec 05 §2 + Session 10: GET returns array newest first; each item carries its mentionedUsers via the batched MentionService lookup")
    void list_returnsArray() throws Exception {
        Comment c1 = fixture(101L, TICKET_ID, AUTHOR_ID, "first", 0L);
        Comment c2 = fixture(102L, TICKET_ID, AUTHOR_ID, "second", 0L);
        when(service.listByTicket(TICKET_ID)).thenReturn(List.of(c2, c1));

        // Batched mentions lookup: only c2 has a mention; c1 has none.
        var alice = new MentionedUserSummary(42L, "alice", "Alice Liddell");
        when(mentions.findForComments(List.of(102L, 101L)))
                .thenReturn(java.util.Map.of(102L, List.of(alice)));

        mvc.perform(get("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(102))
                .andExpect(jsonPath("$[1].id").value(101))
                .andExpect(jsonPath("$[0].mentionedUsers", hasSize(1)))
                .andExpect(jsonPath("$[0].mentionedUsers[0].username").value("alice"))
                .andExpect(jsonPath("$[1].mentionedUsers", hasSize(0)));
    }

    @Test
    @DisplayName("Spec 05 §2: GET on missing ticket → 404 TICKET_NOT_FOUND")
    void list_missingTicket_404() throws Exception {
        when(service.listByTicket(TICKET_ID))
                .thenThrow(new NotFoundException(ErrorCode.TICKET_NOT_FOUND, "Ticket 1 was not found."));

        mvc.perform(get("/tickets/{ticketId}/comments", TICKET_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.TICKET_NOT_FOUND.name()));
    }

    // ---- Spec 05 §3 — version-required + version-conflict --------------------

    @Test
    @DisplayName("Spec 05 §3: PATCH without `version` → 400 VERSION_REQUIRED with details[0].field=version")
    void update_missingVersion_400() throws Exception {
        when(service.update(eq(TICKET_ID), eq(COMMENT_ID), any(), any()))
                .thenThrow(new ValidationException(
                        ErrorCode.VERSION_REQUIRED,
                        "version is required for PATCH /tickets/{ticketId}/comments/{commentId}.",
                        List.of(new ApiError.FieldIssue("version", "must be the version returned by the most recent GET/POST/PATCH"))));

        mvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PatchCommentRequest("new", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_REQUIRED.name()))
                .andExpect(jsonPath("$.details[0].field").value("version"));
    }

    @Test
    @DisplayName("Spec 05 §3: PATCH with stale version → 409 COMMENT_VERSION_CONFLICT")
    void update_staleVersion_409() throws Exception {
        when(service.update(eq(TICKET_ID), eq(COMMENT_ID), any(), any()))
                .thenThrow(new ConflictException(
                        ErrorCode.COMMENT_VERSION_CONFLICT,
                        "Comment 100 was modified by another transaction (yours: v0, server: v1)."));

        mvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PatchCommentRequest("new", 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.COMMENT_VERSION_CONFLICT.name()));
    }

    @Test
    @DisplayName("Spec 05 §3: PATCH happy path → 200 with the new content and bumped version")
    void update_happyPath() throws Exception {
        when(service.update(eq(TICKET_ID), eq(COMMENT_ID), any(), any()))
                .thenReturn(new CommentWithMentions(
                        fixture(COMMENT_ID, TICKET_ID, AUTHOR_ID, "edited", 1L),
                        List.of()));

        mvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PatchCommentRequest("edited", 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---- Spec 05 §5 — delete --------------------------------------------------

    @Test
    @DisplayName("Spec 05 §5: DELETE returns 204 No Content on success")
    void delete_204() throws Exception {
        doNothing().when(service).delete(eq(TICKET_ID), eq(COMMENT_ID), any());

        mvc.perform(delete("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Spec 05 §5: DELETE on missing comment → 404 COMMENT_NOT_FOUND")
    void delete_404() throws Exception {
        doThrow(new NotFoundException(ErrorCode.COMMENT_NOT_FOUND, "Comment 100 was not found on ticket 1."))
                .when(service).delete(eq(TICKET_ID), eq(COMMENT_ID), any());

        mvc.perform(delete("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.COMMENT_NOT_FOUND.name()));
    }

    // ---- Spec 05 §6 — RBAC ----------------------------------------------------

    @Test
    @DisplayName("Spec 05 §6: PATCH by non-author non-admin → 403 COMMENT_FORBIDDEN")
    void update_forbidden_403() throws Exception {
        when(service.update(eq(TICKET_ID), eq(COMMENT_ID), any(), any()))
                .thenThrow(new ForbiddenException(
                        ErrorCode.COMMENT_FORBIDDEN,
                        "You can only edit or delete your own comments."));

        mvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(999L, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PatchCommentRequest("hacked", 0L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.COMMENT_FORBIDDEN.name()));
    }

    @Test
    @DisplayName("Spec 05 §6: DELETE by non-author non-admin → 403 COMMENT_FORBIDDEN")
    void delete_forbidden_403() throws Exception {
        doThrow(new ForbiddenException(
                ErrorCode.COMMENT_FORBIDDEN,
                "You can only edit or delete your own comments."))
                .when(service).delete(eq(TICKET_ID), eq(COMMENT_ID), any());

        mvc.perform(delete("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(999L, Role.DEVELOPER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.COMMENT_FORBIDDEN.name()));
    }

    // ---- Session 06 D2 — URL tenancy 404 -------------------------------------

    @Test
    @DisplayName("Session 06 D2: PATCH on comment that belongs to another ticket → 404 COMMENT_NOT_FOUND")
    void update_wrongTicket_404() throws Exception {
        when(service.update(eq(TICKET_ID), eq(COMMENT_ID), any(), any()))
                .thenThrow(new NotFoundException(
                        ErrorCode.COMMENT_NOT_FOUND,
                        "Comment 100 was not found on ticket 1."));

        mvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", TICKET_ID, COMMENT_ID)
                        .with(as(principal(AUTHOR_ID, Role.DEVELOPER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PatchCommentRequest("hi", 0L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.COMMENT_NOT_FOUND.name()));
    }
}

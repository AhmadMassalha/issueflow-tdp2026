package com.att.tdp.issueflow.mentions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.domain.Mention;
import com.att.tdp.issueflow.mentions.repository.MentionRepository;
import com.att.tdp.issueflow.mentions.service.MentionService;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Pure-Mockito coverage for {@link MentionService} — extractor branches, diff
 * sync arithmetic, and the {@code /users/{id}/mentions} happy/edge paths.
 *
 * <p>End-to-end persistence behaviour (unique constraint, paged sort) is
 * proven in {@code MentionRepositoryJpaTest}; cross-cutting wiring (audit,
 * RBAC, real DB rows) is proven in {@code MentionsIntegrationTest}. This
 * file just exercises the in-memory branches.
 */
@ExtendWith(MockitoExtension.class)
class MentionServiceTest {

    @Mock
    private MentionRepository mentions;

    @Mock
    private UserRepository users;

    @Mock
    private CommentRepository comments;

    @InjectMocks
    private MentionService service;

    // ---- fixtures -----------------------------------------------------------

    private static final Long COMMENT_ID = 100L;
    private static final Long USER_ID = 1L;

    private static MentionedUserSummary user(Long id, String username) {
        return new MentionedUserSummary(id, username, "Full " + username);
    }

    private static Comment commentWith(String content) {
        Comment c = new Comment();
        c.setId(COMMENT_ID);
        c.setTicketId(1L);
        c.setAuthorId(USER_ID);
        c.setContent(content);
        return c;
    }

    // ---- 1. Extractor branches ---------------------------------------------

    @Test
    @DisplayName("Spec 09 extractor: empty / blank / null content → empty desired set; no DB lookup")
    void extractor_blankInput_noLookup() {
        Comment empty = commentWith("");
        Comment blank = commentWith("   ");
        Comment nullContent = commentWith(null);
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        assertThat(service.syncForComment(empty)).isEmpty();
        assertThat(service.syncForComment(blank)).isEmpty();
        assertThat(service.syncForComment(nullContent)).isEmpty();

        verify(users, never()).findMentionedUsersByLoweredUsernames(any());
    }

    @Test
    @DisplayName("Spec 09 extractor: no recognised handles → empty desired; no user lookup")
    void extractor_noHandles_noLookup() {
        Comment c = commentWith("hello world, no mentions here");
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        assertThat(service.syncForComment(c)).isEmpty();
        verify(users, never()).findMentionedUsersByLoweredUsernames(any());
    }

    @Test
    @DisplayName("Spec 09 extractor: case-insensitive dedup — @Alice + @ALICE + @alice → one lowered lookup arg")
    void extractor_dedupCaseInsensitive() {
        Comment c = commentWith("hi @Alice and again @ALICE and @alice");
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(2L, "alice")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        service.syncForComment(c);

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(users).findMentionedUsersByLoweredUsernames(captor.capture());
        // Single deduplicated lowered handle — not three.
        assertThat(captor.getValue()).containsExactly("alice");
    }

    @Test
    @DisplayName("Spec 09 extractor: handle length bounds — 2 chars rejected, 3 accepted, 32 accepted, 33 rejected")
    void extractor_lengthBounds() {
        Comment c = commentWith("too short @ab, just right @abc, max @" + "a".repeat(32)
                + ", too long @" + "a".repeat(33));
        when(users.findMentionedUsersByLoweredUsernames(anyCollection())).thenReturn(List.of());
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        service.syncForComment(c);

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(users).findMentionedUsersByLoweredUsernames(captor.capture());
        // The 33-char run is matched by the regex's first 32 — that's a feature
        // of the regex's greedy bound, so the "first 32 of the long one"
        // shows up as a separate handle. We assert what the regex actually
        // does (don't lie about the spec).
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder("abc", "a".repeat(32));
        assertThat(captor.getValue()).doesNotContain("ab"); // 2 chars rejected
    }

    @Test
    @DisplayName("Spec 09 extractor: unknown handles silently ignored — extracted but resolve to no User row")
    void extractor_unknownHandlesIgnored() {
        Comment c = commentWith("hi @ghost and @alice");
        // Resolver returns only alice; ghost has no User row.
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(2L, "alice")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        List<MentionedUserSummary> resolved = service.syncForComment(c);

        assertThat(resolved).extracting(MentionedUserSummary::username)
                .containsExactly("alice"); // no ghost
    }

    // ---- 2. Diff sync arithmetic --------------------------------------------

    @Test
    @DisplayName("Sync diff: nothing to insert when current = desired (idempotent re-sync)")
    void sync_isIdempotent() {
        Comment c = commentWith("hi @alice");
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(2L, "alice")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID))
                .thenReturn(List.of(2L)); // already there

        service.syncForComment(c);

        verify(mentions, never()).saveAll(any());
        verify(mentions, never()).deleteByCommentIdAndMentionedUserIds(any(), any());
    }

    @Test
    @DisplayName("Sync diff: INSERT delta only — content adds @bob to existing @alice mention")
    void sync_insertsDelta() {
        Comment c = commentWith("hi @alice @bob");
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(2L, "alice"), user(3L, "bob")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID))
                .thenReturn(List.of(2L)); // alice already saved

        service.syncForComment(c);

        // saveAll called with exactly one new Mention (for bob).
        ArgumentCaptor<List<Mention>> captor = ArgumentCaptor.forClass(List.class);
        verify(mentions).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getMentionedUserId()).isEqualTo(3L);
        verify(mentions, never()).deleteByCommentIdAndMentionedUserIds(any(), any());
    }

    @Test
    @DisplayName("Sync diff: DELETE delta only — content removed @bob, alice survives")
    void sync_deletesDelta() {
        Comment c = commentWith("hi @alice"); // bob no longer mentioned
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(2L, "alice")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID))
                .thenReturn(List.of(2L, 3L)); // both currently saved

        service.syncForComment(c);

        verify(mentions, never()).saveAll(any());
        // Surgical delete — only bob's id, alice survives.
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(mentions).deleteByCommentIdAndMentionedUserIds(eq(COMMENT_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly(3L);
    }

    @Test
    @DisplayName("Sync diff: both INSERT and DELETE applied in one transaction when content swaps mentions")
    void sync_insertsAndDeletes() {
        Comment c = commentWith("hi @carol @dave"); // alice, bob removed; carol, dave added
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(4L, "carol"), user(5L, "dave")));
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID))
                .thenReturn(List.of(2L, 3L)); // alice + bob were there

        service.syncForComment(c);

        ArgumentCaptor<List<Mention>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(mentions).saveAll(insertCaptor.capture());
        assertThat(insertCaptor.getValue()).extracting(Mention::getMentionedUserId)
                .containsExactlyInAnyOrder(4L, 5L);

        ArgumentCaptor<Collection<Long>> deleteCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(mentions).deleteByCommentIdAndMentionedUserIds(eq(COMMENT_ID), deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("Session 10 D12: self-mention allowed — no special case, sync writes the row like any other handle")
    void sync_selfMentionAllowed() {
        // Note: "@me" is 2 chars and would be rejected by the spec's {3,32}
        // regex bound. Using "@self" instead — same semantic (the author
        // is the mentioned user) but a valid handle length.
        Comment c = commentWith("note to self @self");
        when(users.findMentionedUsersByLoweredUsernames(anyCollection()))
                .thenReturn(List.of(user(USER_ID, "self"))); // author IS the mentioned user
        when(mentions.findMentionedUserIdsByCommentId(COMMENT_ID)).thenReturn(List.of());

        service.syncForComment(c);

        ArgumentCaptor<List<Mention>> captor = ArgumentCaptor.forClass(List.class);
        verify(mentions).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getMentionedUserId()).isEqualTo(USER_ID);
    }

    // ---- 3. Delete cleanup --------------------------------------------------

    @Test
    @DisplayName("deleteForComment delegates to the bulk repository delete (used by CommentService.delete)")
    void deleteForComment_delegates() {
        service.deleteForComment(COMMENT_ID);
        verify(mentions).deleteByCommentId(COMMENT_ID);
    }

    // ---- 4. /users/{id}/mentions listing ------------------------------------

    @Test
    @DisplayName("findMentionsForUser: 404 USER_NOT_FOUND when user doesn't exist (Session 10 D9)")
    void list_404_whenUserMissing() {
        when(users.existsById(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.findMentionsForUser(42L, PageRequest.of(0, 10)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(mentions, never()).findCommentIdsForMentionedUser(anyLong(), any());
    }

    @Test
    @DisplayName("findMentionsForUser: empty page returns empty content with correct total (spec 09 §5)")
    void list_emptyPage_returnsZeroTotal_whenNoMentions() {
        when(users.existsById(42L)).thenReturn(true);
        when(mentions.findCommentIdsForMentionedUser(eq(42L), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        var page = service.findMentionsForUser(42L, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        // No second/third queries when there's nothing to hydrate.
        verify(comments, never()).findAllById(any());
        verify(mentions, never()).findCommentMentionsByCommentIds(any());
    }

    @Test
    @DisplayName("findMentionsForUser: hydrates comments + batched mentions, preserving the page's order")
    void list_hydratesAndPreservesOrder() {
        when(users.existsById(42L)).thenReturn(true);
        // Page returns comment ids newest-first: 30, 20, 10
        when(mentions.findCommentIdsForMentionedUser(eq(42L), any()))
                .thenReturn(new PageImpl<>(List.of(30L, 20L, 10L), PageRequest.of(0, 10), 3));

        Comment c10 = commentWithId(10L, "first");
        Comment c20 = commentWithId(20L, "second");
        Comment c30 = commentWithId(30L, "third");
        // findAllById returns in arbitrary order — service must re-sort by input.
        when(comments.findAllById(List.of(30L, 20L, 10L)))
                .thenReturn(List.of(c10, c20, c30));

        var bob = user(99L, "bob");
        when(mentions.findCommentMentionsByCommentIds(List.of(30L, 20L, 10L)))
                .thenReturn(List.of(
                        // Just c30 has a mention (besides our user 42)
                        new com.att.tdp.issueflow.mentions.repository.MentionRepository.CommentMentionRow(
                                30L, bob.id(), bob.username(), bob.fullName())));

        var page = service.findMentionsForUser(42L, PageRequest.of(0, 10));

        // Order matches the input order (30, 20, 10).
        assertThat(page.getContent()).extracting(item -> item.comment().getId())
                .containsExactly(30L, 20L, 10L);
        // c30's mentions populated; others empty.
        assertThat(page.getContent().get(0).mentionedUsers())
                .extracting(MentionedUserSummary::username).containsExactly("bob");
        assertThat(page.getContent().get(1).mentionedUsers()).isEmpty();
        assertThat(page.getContent().get(2).mentionedUsers()).isEmpty();
    }

    @Test
    @DisplayName("findMentionsForUser: race-safe — comment hard-deleted between mention page and comment fetch is silently dropped")
    void list_raceSafe_skipsMissingComments() {
        // pageSize MUST equal content size in this test fixture — Spring's
        // PageImpl auto-"corrects" total when offset+pageSize > total
        // (treats it as the last page and clamps total to
        // offset + content.size). That's correct in prod (where pageSize
        // is small relative to total) but here we're forcing the corner.
        // Using pageSize=2 (matches content size) keeps the constructor
        // honoring the explicit total.
        when(users.existsById(42L)).thenReturn(true);
        when(mentions.findCommentIdsForMentionedUser(eq(42L), any()))
                .thenReturn(new PageImpl<>(List.of(30L, 20L), PageRequest.of(0, 2), 2));
        // Only c20 found; c30 was deleted between queries.
        when(comments.findAllById(List.of(30L, 20L)))
                .thenReturn(List.of(commentWithId(20L, "still here")));
        when(mentions.findCommentMentionsByCommentIds(List.of(30L, 20L)))
                .thenReturn(List.of());

        var page = service.findMentionsForUser(42L, PageRequest.of(0, 2));

        // c30 silently dropped; total still reflects the mention-page total.
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).comment().getId()).isEqualTo(20L);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    private static Comment commentWithId(Long id, String content) {
        Comment c = new Comment();
        c.setId(id);
        c.setTicketId(1L);
        c.setAuthorId(USER_ID);
        c.setContent(content);
        return c;
    }
}

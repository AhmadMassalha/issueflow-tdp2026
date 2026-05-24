package com.att.tdp.issueflow.mentions;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.common.web.GlobalExceptionHandler;
import com.att.tdp.issueflow.mentions.api.MentionController;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.service.MentionService;
import com.att.tdp.issueflow.mentions.service.MentionService.MentionResponseItem;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer coverage for {@link MentionController} — envelope shape,
 * pagination conversion (1-indexed wire ↔ 0-indexed Spring), and 404
 * mapping.
 *
 * <p>{@code addFilters = false} per the slice-3 Gotcha
 * ({@code .cursor/rules/30-testing.mdc}) — the security filter chain is
 * out of scope here. {@code @PreAuthorize("isAuthenticated()")} on the
 * controller is not enforced under {@code @WebMvcTest}, so we exercise
 * the happy path directly. Authentication-required behaviour is proven
 * in {@code MentionsIntegrationTest} with the real filter chain.
 */
@WebMvcTest(controllers = MentionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MentionControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MentionService mentions;

    private static final Long USER_ID = 42L;
    private static final Long TICKET_ID = 1L;

    private static Comment comment(Long id, String content) {
        Comment c = new Comment();
        c.setId(id);
        c.setTicketId(TICKET_ID);
        c.setAuthorId(7L);
        c.setContent(content);
        c.setVersion(0L);
        return c;
    }

    // ---- Spec 09 §3-§4 — envelope + content shape --------------------------

    @Test
    @DisplayName("Spec 09 §3 + §4: GET /users/{id}/mentions returns standard envelope; each data[] item is the full comment shape including mentionedUsers")
    void should_return200_withStandardEnvelope() throws Exception {
        var alice = new MentionedUserSummary(USER_ID, "alice", "Alice Liddell");
        var bob = new MentionedUserSummary(99L, "bob", "Bob Roberts");
        // Two comments, both mention alice; the second also mentions bob.
        var items = List.of(
                new MentionResponseItem(comment(102L, "hi @alice @bob"), List.of(alice, bob)),
                new MentionResponseItem(comment(101L, "hello @alice"), List.of(alice)));
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(items, PageRequest.of(0, 20), 2));

        mvc.perform(get("/users/{userId}/mentions", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                // Slice 10 D6: envelope is {data, total, page, pageSize} with 1-indexed page.
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                // Each data[] item is the full CommentResponse shape (D7).
                .andExpect(jsonPath("$.data[0].id").value(102))
                .andExpect(jsonPath("$.data[0].ticketId").value(TICKET_ID))
                .andExpect(jsonPath("$.data[0].content").value("hi @alice @bob"))
                .andExpect(jsonPath("$.data[0].mentionedUsers", hasSize(2)))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].fullName").value("Alice Liddell"))
                .andExpect(jsonPath("$.data[1].id").value(101))
                .andExpect(jsonPath("$.data[1].mentionedUsers", hasSize(1)));
    }

    // ---- Spec 09 §5 — empty page -------------------------------------------

    @Test
    @DisplayName("Spec 09 §5: page out of range returns empty data[] with correct total (no 404)")
    void should_returnEmptyData_withCorrectTotal_whenPageOutOfRange() throws Exception {
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 3));

        mvc.perform(get("/users/{userId}/mentions", USER_ID)
                        .param("page", "6").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.total").value(3));
    }

    // ---- 404: user doesn't exist -------------------------------------------

    @Test
    @DisplayName("Session 10 D9: GET on missing user → 404 USER_NOT_FOUND")
    void should_return404_whenUserMissing() throws Exception {
        when(mentions.findMentionsForUser(eq(999L), any(Pageable.class)))
                .thenThrow(new NotFoundException(ErrorCode.USER_NOT_FOUND, "User 999 was not found."));

        mvc.perform(get("/users/{userId}/mentions", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.name()));
    }

    // ---- pagination conversion ---------------------------------------------

    @Test
    @DisplayName("Pagination — defaults: omitted page/pageSize → service called with PageRequest.of(0, 20)")
    void should_useDefaults_whenOmitted() throws Exception {
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/users/{userId}/mentions", USER_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(mentions).findMentionsForUser(eq(USER_ID), captor.capture());
        Assertions.assertThat(captor.getValue().getPageNumber()).isZero(); // wire 1 - 1 = 0
        Assertions.assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Pagination — wire page=3 maps to Spring page=2 (1-indexed → 0-indexed conversion)")
    void should_convert1IndexedPage_to0Indexed() throws Exception {
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mvc.perform(get("/users/{userId}/mentions", USER_ID)
                        .param("page", "3").param("pageSize", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(mentions).findMentionsForUser(eq(USER_ID), captor.capture());
        Assertions.assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Pagination — pageSize clamped to MAX_PAGE_SIZE=100 when client asks for more")
    void should_clampPageSize_whenOversize() throws Exception {
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mvc.perform(get("/users/{userId}/mentions", USER_ID)
                        .param("pageSize", "10000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(mentions).findMentionsForUser(eq(USER_ID), captor.capture());
        Assertions.assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Pagination — page < 1 on the wire clamps to Spring page 0 (forgiving, not 400)")
    void should_clampSubOnePage_toFirst() throws Exception {
        when(mentions.findMentionsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/users/{userId}/mentions", USER_ID).param("page", "-3"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(mentions).findMentionsForUser(eq(USER_ID), captor.capture());
        Assertions.assertThat(captor.getValue().getPageNumber()).isZero();
    }
}

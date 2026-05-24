package com.att.tdp.issueflow.mentions.service;

import com.att.tdp.issueflow.comments.domain.Comment;
import com.att.tdp.issueflow.comments.repository.CommentRepository;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.common.web.ErrorCode;
import com.att.tdp.issueflow.mentions.api.MentionedUserSummary;
import com.att.tdp.issueflow.mentions.domain.Mention;
import com.att.tdp.issueflow.mentions.repository.MentionRepository;
import com.att.tdp.issueflow.users.repository.UserRepository;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the {@code mentions} feature (spec 09).
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Sync mentions for a comment</b> — called by {@code CommentService}
 *       on create and update, in the same {@code @Transactional} (Session
 *       10 D1). Extract handles from the content, resolve them
 *       case-insensitively against existing users, and diff against the
 *       current persisted set: INSERT new ones, DELETE removed ones,
 *       leave matched-on-both untouched (Session 10 D3 — preserves
 *       {@code mention.created_at} on rows that survive an edit, which
 *       the spec §3 sort relies on).</li>
 *   <li><b>List comments that mention a user</b> — backs
 *       {@code GET /users/{userId}/mentions} with the standard
 *       {@code PageResponse} envelope, newest first by
 *       {@code mention.created_at}.</li>
 * </ol>
 *
 * <p><b>Why no audit row on sync (Session 10 D10):</b> mentions are
 * derived data from {@code comment.content}; the COMMENT.UPDATE/CREATE
 * audit row already records the source change. Adding MENTION.* audit
 * rows would 2-3x the audit volume for no incremental forensic info.
 *
 * <p><b>Why no DI on {@code CommentService}:</b> the mention extractor
 * is invoked by {@code CommentService}, not the other way around — so
 * the dependency edge is {@code CommentService → MentionService}, never
 * the reverse. Listing mentioned-comments is a read path that uses
 * {@code CommentRepository} directly, not via the comment service (no
 * RBAC question — anyone can list any user's mentions, Session 10 D9
 * scope).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MentionService {

    /**
     * Spec 09 regex. Matches the username pattern from spec 01 verbatim
     * — 3-32 chars, letters/digits/underscore. The {@code @} sentinel is
     * outside the capturing group so {@code group(1)} returns the bare
     * handle, ready for lowercasing and the IN-list lookup.
     */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,32})");

    private final MentionRepository mentions;
    private final UserRepository users;
    private final CommentRepository comments;

    // ---- sync (called from CommentService) ---------------------------------

    /**
     * Sync the mention rows for {@code comment} to match its current
     * {@code content}. Idempotent; calling twice with the same content
     * is a no-op (the diff produces empty INSERT and DELETE sets).
     *
     * <p>Returns the post-sync {@link MentionedUserSummary} list so the
     * caller can plug it straight into the response without an extra
     * query. Empty list when the content has no recognized handles.
     */
    public List<MentionedUserSummary> syncForComment(Comment comment) {
        // 1. Resolve the desired set from content. Empty content → empty desired set → DELETE all.
        List<MentionedUserSummary> desiredUsers = resolveMentionedUsers(comment.getContent());
        Set<Long> desiredIds = new HashSet<>(desiredUsers.size());
        for (MentionedUserSummary u : desiredUsers) {
            desiredIds.add(u.id());
        }

        // 2. Diff against the current persisted set.
        Set<Long> currentIds = new HashSet<>(
                mentions.findMentionedUserIdsByCommentId(comment.getId()));

        Set<Long> toInsert = new HashSet<>(desiredIds);
        toInsert.removeAll(currentIds);

        Set<Long> toDelete = new HashSet<>(currentIds);
        toDelete.removeAll(desiredIds);

        // 3. Apply both deltas. Order doesn't matter (unique constraint isn't
        //    on a sequence), but doing INSERTs first avoids any momentary
        //    "0 mentions" window inside the same transaction (harmless given
        //    transactional isolation, but more pleasing to read in a debugger).
        if (!toInsert.isEmpty()) {
            List<Mention> newRows = toInsert.stream()
                    .map(uid -> new Mention(comment.getId(), uid))
                    .toList();
            mentions.saveAll(newRows);
        }
        if (!toDelete.isEmpty()) {
            // One bulk DELETE statement — see the repository method's
            // JavaDoc for why we don't load-then-delete the rows.
            mentions.deleteByCommentIdAndMentionedUserIds(comment.getId(), toDelete);
        }

        return desiredUsers;
    }

    /**
     * Cleanup hook for {@code CommentService.delete} (Session 10 D5).
     * Bulk DELETE rather than load-then-delete; mention rows have no
     * cascading concerns of their own. Idempotent — returns 0 if the
     * comment had no mentions, which is fine.
     */
    public void deleteForComment(Long commentId) {
        mentions.deleteByCommentId(commentId);
    }

    // ---- read paths (called from controllers) -------------------------------

    /**
     * Fetch the rich {@link MentionedUserSummary} list for a single comment.
     * Used by the comment-controller responses for create/update/read paths.
     */
    @Transactional(readOnly = true)
    public List<MentionedUserSummary> findForComment(Long commentId) {
        return mentions.findMentionedUsersByCommentId(commentId);
    }

    /**
     * Batched lookup keyed by comment id. Caller (the comments-list
     * controller) hands in the page's comment-id set and gets back a map
     * ready for {@code map.getOrDefault(id, List.of())} on each row.
     *
     * <p>Single round-trip regardless of page size — the explicit
     * empty-input guard protects against {@code IN ()} driver
     * misbehavior (no driver currently handles it consistently).
     */
    @Transactional(readOnly = true)
    public Map<Long, List<MentionedUserSummary>> findForComments(Collection<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MentionedUserSummary>> grouped = new HashMap<>();
        for (var row : mentions.findCommentMentionsByCommentIds(commentIds)) {
            grouped.computeIfAbsent(row.commentId(), k -> new java.util.ArrayList<>()).add(row.toUser());
        }
        return grouped;
    }

    // ---- /users/{userId}/mentions listing -----------------------------------

    /**
     * Paged listing for spec 09 §3. Two narrow queries:
     * <ol>
     *   <li>Page of {@code (commentId, mention.createdAt)} sorted by
     *       {@code createdAt DESC} — the page metadata (total, page)
     *       comes from Spring's auto-generated count query.</li>
     *   <li>Batch {@code findAllById(commentIds)} for the comment rows,
     *       then batched mention fetch for those rows' mentioned users.</li>
     * </ol>
     *
     * <p>Comment hydration uses {@code findAllById} (no order guarantee)
     * and we re-sort client-side by the input id order, which IS the
     * desired output order. Three queries total per page request:
     * {@code mention-id page} + {@code count(*) over mentions} +
     * {@code comments WHERE id IN (page)} + {@code mention-batch for that
     * page} — four if you count the embedded count. Constant w.r.t.
     * page size.
     *
     * @throws NotFoundException with {@link ErrorCode#USER_NOT_FOUND}
     *         when no user exists with id {@code userId} (Session 10 D9).
     */
    @Transactional(readOnly = true)
    public Page<MentionResponseItem> findMentionsForUser(Long userId, Pageable pageable) {
        if (!users.existsById(userId)) {
            throw new NotFoundException(
                    ErrorCode.USER_NOT_FOUND, "User " + userId + " was not found.");
        }

        Page<Long> commentIdPage = mentions.findCommentIdsForMentionedUser(userId, pageable);
        if (commentIdPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, commentIdPage.getTotalElements());
        }

        List<Long> orderedIds = commentIdPage.getContent();
        Map<Long, Comment> byId = new LinkedHashMap<>();
        for (Comment c : comments.findAllById(orderedIds)) {
            byId.put(c.getId(), c);
        }
        Map<Long, List<MentionedUserSummary>> mentionsByComment = findForComments(orderedIds);

        List<MentionResponseItem> items = orderedIds.stream()
                .map(id -> {
                    Comment c = byId.get(id);
                    if (c == null) {
                        return null; // comment was hard-deleted between query 1 and query 2 — race-safe skip
                    }
                    return new MentionResponseItem(
                            c, mentionsByComment.getOrDefault(id, List.of()));
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new PageImpl<>(items, pageable, commentIdPage.getTotalElements());
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Spec 09 extractor: regex sweep + lowercase + dedup + batch DB lookup.
     * Returns an empty list (not null) when the content has no recognized
     * handles or when no extracted handle resolves to a known user.
     */
    List<MentionedUserSummary> resolveMentionedUsers(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        Set<String> deduped = new LinkedHashSet<>(); // preserve first-seen order for stable debugging
        Matcher m = MENTION_PATTERN.matcher(content);
        while (m.find()) {
            deduped.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        if (deduped.isEmpty()) {
            return List.of();
        }

        List<MentionedUserSummary> resolved = users.findMentionedUsersByLoweredUsernames(deduped);
        return Collections.unmodifiableList(resolved);
    }

    /**
     * Aggregate returned by {@link #findMentionsForUser(Long, Pageable)} —
     * the comment row plus its full mentioned-users list. Controller maps
     * this to {@code CommentResponse.from(comment, mentions)}.
     */
    public record MentionResponseItem(Comment comment, List<MentionedUserSummary> mentionedUsers) {
    }
}

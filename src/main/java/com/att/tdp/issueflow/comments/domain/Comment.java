package com.att.tdp.issueflow.comments.domain;

import com.att.tdp.issueflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Comment on a ticket.
 *
 * <p>Spec 05 entity table:
 * <pre>
 *   id          BIGINT PK
 *   ticketId    BIGINT FK → tickets  (required, must exist)
 *   authorId    BIGINT FK → users    (required; derived from JWT principal)
 *   content     TEXT                 (required, max 5000)
 *   version     BIGINT               (@Version)
 *   createdAt   TIMESTAMPTZ
 *   updatedAt   TIMESTAMPTZ
 * </pre>
 *
 * <p><b>No soft-delete column.</b> Spec 05 §5 explicitly mandates hard delete
 * — comments are not in slice 9's {@code @SQLDelete}/{@code @SQLRestriction}
 * scope. This is the first entity in the codebase without a {@code deletedAt}
 * slot, and the omission is deliberate. Do not "add it for consistency" with
 * Ticket/Project; if a future spec amendment makes comments soft-deletable,
 * that's its own slice with its own contract change.
 *
 * <p><b>FKs as plain {@code Long}, not {@code @ManyToOne}:</b> same rationale
 * as {@link com.att.tdp.issueflow.tickets.domain.Ticket} (Session 05) and
 * {@link com.att.tdp.issueflow.projects.domain.Project} (Session 04). Keeps
 * the response projection cheap, avoids fetch-mode questions, and lines up
 * with the flat {@code {id, ticketId, authorId, content, version, …}}
 * response shape.
 *
 * <p><b>Optimistic locking (ADR 0001):</b> {@code @Version Long version} is
 * incremented by Hibernate on UPDATE. Spec 05 §3: PATCH requires the client
 * to echo back the loaded version; missing → 400 {@code VERSION_REQUIRED},
 * stale → 409 {@code COMMENT_VERSION_CONFLICT}. Two-layer enforcement:
 * service pre-check + flush-time fallback via
 * {@link com.att.tdp.issueflow.common.web.GlobalExceptionHandler#handleOptimisticLock}
 * (the {@code Comment} arm was pre-seeded in slice 5 — see Session 05).
 *
 * <p><b>Index on {@code ticket_id}:</b> the list-by-ticket query is the
 * dominant access pattern and it filters on this column. Without an index,
 * even modest comment volumes would force a sequential scan per list call.
 */
@Entity
@Table(
        name = "comments",
        indexes = {@Index(name = "idx_comments_ticket_id", columnList = "ticket_id")}
)
@Getter
@Setter
@NoArgsConstructor
public class Comment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * JPA optimistic-locking version. Starts at 0 on insert; Hibernate
     * increments on every UPDATE and uses it in the {@code UPDATE … WHERE
     * id=? AND version=?} clause. Mismatches surface as
     * {@code ObjectOptimisticLockingFailureException}.
     */
    @Version
    @Column(nullable = false)
    private Long version;
}

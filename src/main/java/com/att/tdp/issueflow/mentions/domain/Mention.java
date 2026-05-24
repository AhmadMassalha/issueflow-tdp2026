package com.att.tdp.issueflow.mentions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Join row recording that a {@link com.att.tdp.issueflow.comments.domain.Comment}
 * mentions a specific user (spec 09 §Entity).
 *
 * <p><b>Does NOT extend {@code BaseEntity}.</b> Same rationale as
 * {@code AuditLog}: mention rows are write-once derived data. There is no
 * "edit a past mention" code path — when a comment's content changes, the
 * sync diff INSERTs new rows and DELETEs gone rows (Session 10 D3); rows
 * are never updated in place. So {@code updatedAt} would be a lie, and a
 * single {@code @CreationTimestamp Instant createdAt} suffices. No
 * {@code @Version} either — without an UPDATE path there's no lost-write
 * race to guard against.
 *
 * <p><b>FK references stored as plain {@code Long}</b> ({@code commentId},
 * {@code mentionedUserId}) — no {@code @ManyToOne}. Same pattern as
 * {@code Ticket.projectId}, {@code Comment.ticketId}, {@code AuditLog.performedBy}
 * (Session 04/05 convention). Listing queries use explicit JPQL JOIN against
 * {@code Comment} instead of pulling in association lazy-loads.
 *
 * <p><b>Unique constraint {@code (comment_id, mentioned_user_id)}</b> caps
 * each comment at one mention per user — naturally handles the
 * "@alice @ALICE" duplicate within a single comment (we also dedup
 * application-side, Session 10 D2, but the DB constraint is the final
 * authoritative answer if a future code path forgets). Self-mention is
 * allowed (Session 10 D12) — the constraint isn't on mentioner identity.
 *
 * <p><b>Indexes:</b> the "show me what mentions user X, newest first"
 * query (the only spec-required listing, §3) reads
 * {@code WHERE mentioned_user_id = ? ORDER BY created_at DESC} — covered
 * by {@code idx_mention_user_created}. The "delete all mentions for
 * comment C" path on comment hard-delete (Session 10 D5) reads
 * {@code WHERE comment_id = ?} — covered by the unique constraint's
 * implicit index on its leading column ({@code comment_id}).
 */
@Entity
@Table(
        name = "mentions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_mention_comment_user",
                        columnNames = { "comment_id", "mentioned_user_id" })
        },
        indexes = {
                @Index(
                        name = "idx_mention_user_created",
                        columnList = "mentioned_user_id,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Mention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "mentioned_user_id", nullable = false)
    private Long mentionedUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Mention(Long commentId, Long mentionedUserId) {
        this.commentId = commentId;
        this.mentionedUserId = mentionedUserId;
    }
}

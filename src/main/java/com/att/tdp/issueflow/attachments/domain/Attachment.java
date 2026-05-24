package com.att.tdp.issueflow.attachments.domain;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * Ticket attachment — stores the binary payload + metadata in one row
 * (spec 11 §"Entity"). Session 12 D1 — DB-backed storage so the file +
 * its audit row land in the same transaction.
 *
 * <p><b>Does NOT extend {@code BaseEntity}.</b> Same rationale as
 * {@code AuditLog} and {@code Mention} (Session 12 D6): attachments are
 * write-once — there is no PATCH endpoint, no in-place edits, no
 * rename, no re-upload. So {@code updatedAt} would be a lie and
 * {@code @Version} would guard a race that doesn't exist. Only
 * {@code createdAt} via {@code @CreationTimestamp} is meaningful.
 *
 * <p><b>FK as plain {@code Long}:</b> {@code ticketId} and
 * {@code uploadedBy} stored as ids, no {@code @ManyToOne}. Same project
 * convention as {@code Ticket.projectId}, {@code Comment.ticketId},
 * {@code Mention.commentId} — keeps the metadata response cheap, avoids
 * dragging an eager Ticket fetch into every download.
 *
 * <p><b>{@code @Lob byte[] data}:</b> stored as PostgreSQL {@code BYTEA}
 * (and H2's BLOB equivalent in tests). {@code FetchType.LAZY} on the
 * {@code @Basic} so the bytes are NOT fetched when we load metadata for
 * the list / DELETE / audit paths — only the download endpoint
 * explicitly pulls them. {@link com.att.tdp.issueflow.attachments.repository.AttachmentRepository}
 * exposes both a metadata-only projection and a "with-bytes" loader for
 * exactly this distinction.
 *
 * <p><b>{@code sizeBytes}:</b> denormalised from {@code data.length} at
 * save time so the metadata endpoint can serve it without materialising
 * the lob. Set in {@code AttachmentService.upload(...)}; never updated.
 *
 * <p><b>Soft-delete behavior (Session 12 D7):</b> attachments do NOT
 * extend {@code BaseEntity}, so they have no {@code deleted_at} column
 * and no {@code @SQLRestriction}. The parent {@link com.att.tdp.issueflow.tickets.domain.Ticket}'s
 * soft-delete does NOT cascade — attachment rows outlive their soft-
 * deleted parent (mirrors ADR 0002: soft-delete is reversible, so
 * dependent rows must survive). New uploads onto a soft-deleted ticket
 * fail at the service layer because {@code tickets.findById()} returns
 * empty (parent is hidden by its own {@code @SQLRestriction}).
 *
 * <p><b>Index:</b> {@code idx_attachment_ticket} covers the inevitable
 * "show me all attachments for ticket X" listing query (slice-12 service
 * uses it indirectly when a future "list attachments" endpoint lands).
 */
@Entity
@Table(
        name = "attachments",
        indexes = {
                @Index(name = "idx_attachment_ticket", columnList = "ticket_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    /**
     * The bytes themselves. {@link FetchType#LAZY} so list / delete /
     * audit paths don't materialise multi-MB payloads they don't need.
     * Hibernate proxies the field via a private interceptor field — we
     * never touch it ourselves except through the getter, which triggers
     * the SELECT on the first access inside the open session.
     *
     * <p><b>Why {@code columnDefinition = "BYTEA"}:</b> Hibernate's
     * {@code H2Dialect} generates {@code blob} for {@code @Lob byte[]},
     * but the test datasource runs H2 in {@code MODE=PostgreSQL} where
     * {@code BLOB} is not a recognised type ({@code Unknown data type:
     * "BLOB"} at DDL time). PostgreSQL itself uses {@code BYTEA}; H2 in
     * PG-mode accepts it as the canonical PG alias. Setting it
     * explicitly makes the DDL portable across both, with no test-only
     * shim required.
     *
     * <p><b>Why {@code @JdbcTypeCode(Types.VARBINARY)}:</b> {@code @Lob}
     * tells Hibernate to use the JDBC {@code BLOB} type code for runtime
     * parameter binding. The PostgreSQL JDBC driver maps {@code BLOB} to
     * the "Large Object" facility (a bigint OID pointing to a row in
     * {@code pg_largeobject}) — which mismatches a {@code BYTEA} column
     * and surfaces at runtime as
     * {@code ERROR: column "data" is of type bytea but expression is of
     * type bigint}. The DDL fix above is not enough; we also need to
     * override the runtime JDBC type. {@code VARBINARY} binds the array
     * inline as raw bytes, which is what {@code BYTEA} expects. H2-in-PG-
     * mode also accepts this; no test-only shim required. Caught by the
     * post-implementation smoke test against real PostgreSQL — H2
     * silently accepted both bindings, hiding the mismatch.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.VARBINARY)
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] data;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

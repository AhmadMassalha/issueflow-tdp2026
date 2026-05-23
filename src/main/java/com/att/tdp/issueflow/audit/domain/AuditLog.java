package com.att.tdp.issueflow.audit.domain;

import com.att.tdp.issueflow.common.enums.Actor;
import com.att.tdp.issueflow.common.enums.AuditAction;
import com.att.tdp.issueflow.common.enums.EntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only audit row (spec 06).
 *
 * <p><b>Does NOT extend {@code BaseEntity}.</b> Audit rows are immutable —
 * there is no "edit a past audit row" code path — so an {@code updatedAt}
 * column would be misleading at best and a bug magnet at worst. Uses a
 * single {@code @CreationTimestamp Instant timestamp} field instead.
 *
 * <p>Indexes are tuned for the spec §6 filter combinations: the most common
 * audit-reviewer query is "what happened to ticket N?" → covered by
 * {@code (entity_type, entity_id)}; the "what did user X do?" query →
 * covered by {@code (performed_by)}; the "what happened recently?" sort
 * → covered by {@code (timestamp)}.
 */
@Entity
@Table(
        name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_audit_performed_by", columnList = "performed_by"),
                @Index(name = "idx_audit_timestamp", columnList = "timestamp")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    /**
     * Nullable. Spec 06 allows the column to be empty for actions that
     * aren't entity-scoped (LOGIN/LOGOUT, future bulk operations). All
     * current CRUD callers pass a real id.
     */
    @Column(name = "entity_id")
    private Long entityId;

    /**
     * FK to {@code users}, nullable. Stored as a plain {@code Long}
     * (Session 04/05 convention — no {@code @ManyToOne}). {@code null} for
     * SYSTEM-actor rows (background jobs, AdminSeeder).
     */
    @Column(name = "performed_by")
    private Long performedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Actor actor;

    /**
     * Optional before/after snapshot as a JSON string. Stored as {@code TEXT}
     * rather than Postgres-native {@code JSONB} per spec wording ("stored as
     * string for simplicity") — keeps the H2 test path uniform with prod and
     * spares us a custom Hibernate type for slice 7. Slice 15 (polish) may
     * convert if a real reviewer use case demands JSON path queries.
     */
    @Column(columnDefinition = "TEXT")
    private String diff;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant timestamp;
}

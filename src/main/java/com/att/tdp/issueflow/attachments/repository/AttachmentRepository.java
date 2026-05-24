package com.att.tdp.issueflow.attachments.repository;

import com.att.tdp.issueflow.attachments.domain.Attachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence boundary for {@link Attachment}.
 *
 * <p>{@code findById} returns the entity with the {@code @Lob byte[]}
 * still proxied (lazy) — accessing {@code att.getData()} triggers the
 * SELECT on demand. That's intentional: the download endpoint needs
 * the bytes, but the DELETE / metadata paths don't.
 *
 * <p>Future "list attachments for a ticket" endpoint can use
 * {@link #findByTicketIdOrderByIdAsc(Long)} (or a projection variant
 * that omits {@code data}). Not exposed via a controller this slice
 * because the spec doesn't list it.
 *
 * <p>{@link #deleteByTicketId(Long)} is a bulk operation that bypasses
 * Hibernate's entity-level lifecycle (no per-row entity load). Provided
 * for a future "hard delete + purge attachments" admin endpoint; NOT
 * called from {@code TicketService.delete()} this slice (Session 12
 * D8 — soft-delete doesn't cascade).
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByTicketIdOrderByIdAsc(Long ticketId);

    @Modifying
    @Query("DELETE FROM Attachment a WHERE a.ticketId = :ticketId")
    int deleteByTicketId(@Param("ticketId") Long ticketId);
}

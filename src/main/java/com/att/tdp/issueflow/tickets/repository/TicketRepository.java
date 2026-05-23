package com.att.tdp.issueflow.tickets.repository;

import com.att.tdp.issueflow.tickets.domain.Ticket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence boundary for {@link Ticket}.
 *
 * <p>Spec 04 §12 requires {@code GET /tickets?projectId=…}; the controller
 * delegates here. The "exclude soft-deleted" half of §12 becomes automatic
 * in slice 9 when {@code @SQLRestriction("deleted_at IS NULL")} is added to
 * {@link Ticket} — no query change needed here.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByProjectId(Long projectId);
}

package com.att.tdp.issueflow.comments.repository;

import com.att.tdp.issueflow.comments.domain.Comment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence boundary for {@link Comment}.
 *
 * <p>{@link #findByTicketIdOrderByCreatedAtDesc} powers the
 * {@code GET /tickets/{ticketId}/comments} endpoint (spec 05 §2 — newest
 * first).
 *
 * <p>{@link #findByIdAndTicketId} encodes the URL-tenancy invariant
 * (Session 06 D2) at the query layer: if a client requests
 * {@code /tickets/5/comments/42} but comment 42 belongs to ticket 7, the
 * query returns empty and the service raises 404 {@code COMMENT_NOT_FOUND}
 * — same response shape as "comment does not exist", so the wrong-ticket
 * case cannot be used as an existence oracle.
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByTicketIdOrderByCreatedAtDesc(Long ticketId);

    Optional<Comment> findByIdAndTicketId(Long id, Long ticketId);
}

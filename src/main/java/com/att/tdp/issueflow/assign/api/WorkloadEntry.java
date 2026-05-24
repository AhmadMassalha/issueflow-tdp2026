package com.att.tdp.issueflow.assign.api;

/**
 * One row in the response of {@code GET /projects/{projectId}/workload}
 * (spec 12 §Endpoint). Also the JPQL projection target for
 * {@code UserRepository.findWorkloadForProject(...)}.
 *
 * <p>Shape per spec verbatim:
 * <pre>
 *   { "userId": 42, "username": "alice", "openTicketCount": 7 }
 * </pre>
 *
 * <p><b>Counted tickets</b> are those where {@code assigneeId = userId} AND
 * {@code projectId = pid} AND {@code status != DONE}. Soft-deleted tickets
 * are excluded automatically by the {@code @SQLRestriction} on {@code Ticket}
 * (slice 9) — so a developer whose only ticket in this project is
 * soft-deleted will report {@code openTicketCount = 0} AND will not be a
 * member of the project (their tickets don't count toward membership
 * either). Both halves use the same JPQL JOIN against {@code Ticket},
 * so the consistency is structural — see ADR 0007.
 *
 * <p>JPQL projection target: record components must match constructor
 * argument order exactly ({@code u.id, u.username, count(t)}) or
 * Hibernate throws at startup.
 */
public record WorkloadEntry(
        Long userId,
        String username,
        Long openTicketCount
) {
}

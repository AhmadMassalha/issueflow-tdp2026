# Spec 07 — Ticket dependencies

## Endpoints
- `POST /tickets/{ticketId}/dependencies` body `{ "blockedBy": <ticketId> }`
- `GET /tickets/{ticketId}/dependencies`
- `DELETE /tickets/{ticketId}/dependencies/{blockerId}`

## Entity
`ticket_dependency(ticket_id, blocker_id)` — composite PK, both FKs cascade on ticket hard-delete (which never happens in our API — see spec 08). Modeled as a JPA entity with an `@IdClass` (or `@EmbeddedId`) holding the composite key.

## Acceptance criteria
1. Both tickets must exist and belong to the **same project**, else 422 `DEPENDENCY_DIFFERENT_PROJECT`.
2. Self-dependency (`ticketId == blockerId`) → 422 `DEPENDENCY_SELF`.
3. Duplicate dependency → 409 `DEPENDENCY_EXISTS`.
4. Cycle creation must be detected and rejected → 422 `DEPENDENCY_CYCLE`. Walk the blocker graph (BFS) from `blockerId` up to a max depth (e.g. 100); if `ticketId` is reachable, reject.
5. `GET` returns an array of `{ id, title, status }` for each blocker.
6. `DELETE` removes the row; 404 `DEPENDENCY_NOT_FOUND` if not present.
7. **Cross-feature** — see spec 04 §9: a ticket cannot transition to DONE while any blocker is not `DONE` itself. Enforced in `TicketService.transition(...)` by querying dependencies before save.
8. Audit log: `CREATE` on add, `DELETE` on remove, with `entityType = DEPENDENCY` and `entityId` = the dependency row id (or composite encoded as a string).

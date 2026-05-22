# ADR 0002 — Soft delete does not cascade

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** Ahmad Massalha

## Context
Spec 08 requires soft-delete on `Project` and `Ticket` via `@SQLDelete` + `@SQLRestriction`. The requirements PDF does not say what should happen to a project's tickets when the project itself is soft-deleted.

## Options considered
1. **Cascade `deletedAt` to tickets** — when a project is soft-deleted, write the same `deletedAt` to all its tickets.
2. **No cascade** — only mark the project as deleted. Tickets remain physically present but are hidden from `GET /tickets` indirectly (their project is hidden).
3. **Cascade but allow per-ticket restore** — same as 1 but `POST /projects/{id}/restore` does NOT undo ticket deletions; ADMINs would have to restore tickets one by one.

## Decision
Option 2 — **no cascade**.

Rationale:
- Spec 08 §4 hints at this: "Restoring a project leaves its tickets' `deletedAt` untouched." If we cascaded on delete, we'd have to also cascade on restore for symmetry, and the spec explicitly says not to.
- Auditability: ticket history is preserved exactly as the user last left it.
- Simpler implementation — no extra SQL, no event listeners.

## Consequences
- Soft-deleted projects can be restored cleanly and their tickets come back in whatever state they were in.
- `GET /tickets?projectId=<deleted>` returns empty (the project filter excludes the parent), so tickets become orphaned-by-filter. This is acceptable because:
  - `/tickets/deleted?projectId=<deleted>` (ADMIN) still surfaces them.
  - Any analytics view that wants them can use the same admin-only query.
- Document this explicitly in `run.md` so reviewers understand the choice when they test soft-delete.

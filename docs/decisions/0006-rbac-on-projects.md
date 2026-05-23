# ADR 0006 — RBAC scope on `/projects` endpoints

- **Status:** Accepted
- **Date:** 2026-05-23
- **Deciders:** Ahmad Massalha (Session 04 D2)

## Context

Spec 03 (Projects) is silent on per-endpoint authorization. After slice 3, every
endpoint is behind authentication (spec 02 §3), but the spec doesn't enumerate
which `/projects` operations should be additionally role-gated.

Two parts of the surrounding spec interact with this decision:

- Spec 03 §1 requires the request body to carry `ownerId`. The README example
  shows a developer-level user creating a project owned by an arbitrary other
  user. Read literally, that implies cross-user creation is allowed.
- Spec 08 (Soft Delete) says deleted records are hidden from standard responses
  and **restorable by ADMIN users**, but it does *not* say only ADMINs may
  delete in the first place.
- Spec 13 (Auto-assign) needs every developer to be able to read every project
  so workload calculations work.

ADR 0005 set the precedent for being deliberate (not default) about RBAC scope
per resource.

## Options considered

1. **Open to any authenticated user on all 5 endpoints.** Read, create, patch,
   and hard-delete are equally available. Spec-literal.
2. **`DELETE` ADMIN-only.** Reads/create/patch open. Defensive against a rogue
   or accidental wipe of every project by a single developer account.
3. **Owner-or-ADMIN model for mutations.** Only the project's `ownerId` (or any
   ADMIN) may patch or delete. Reads open. Tighter, closer to GitHub-style
   ownership semantics.

## Decision

Option **(1)** — open to any authenticated user on all five endpoints.

## Consequences

- Matches the spec and the README example literally. Reviewers see no surprise
  behavior relative to the documented contract.
- Reads (`GET /projects`, `GET /projects/{id}`) being open is required by spec
  13 (Auto-assign), so a future tightening must work around that.
- **Hard delete is open by design.** This is the riskiest part of the choice.
  The mitigation is that slice 9 will convert hard delete to **soft delete**
  for all eligible entities (per ADR 0002), at which point a `DELETE` becomes
  recoverable by an ADMIN via spec-08's restore endpoint. The window where a
  developer can permanently destroy a project is the gap between slice 4 and
  slice 9 — during that window the assignment is being graded slice-by-slice,
  not run in production.
- If a production deployment ever needs option (2) or (3), the change is
  additive: a single `@PreAuthorize` annotation per affected service method
  (option 2), or a custom `@PreAuthorize("@projectGuard.canMutate(...)")`
  expression backed by a small `ProjectGuard` component (option 3). Neither
  requires reworking the service or controller surface.
- This ADR is *intentionally permissive*. It exists so the reviewer sees that
  the open delete is a recorded decision, not an oversight or copy-paste from
  the `/users` ADR.

## Related

- ADR 0002 — no soft-delete cascade (sets the slice-9 framing for "what does
  delete actually mean across the system").
- ADR 0005 — RBAC scope on `/users` (the contrasting case where the same
  reasoning led to a more restrictive split, because privilege escalation was
  on the table for `/users` in a way it isn't for `/projects`).

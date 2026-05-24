# ADR 0007 — Project membership definition (slice 13)

- **Status:** Accepted
- **Date:** 2026-05-24
- **Deciders:** Ahmad Massalha (Session 13 D1)

## Context

Spec 12 (auto-assignment by workload) requires the algorithm to "find all users
with role DEVELOPER who are members of the project." Spec 04 §4 also requires
the ticket-create flow to validate that a supplied `assigneeId` is a developer
"active in that project" — the same membership concept under a different name.

Neither the spec docs nor the README defines `project_members`. There is no
join table, no `ProjectMember` entity, and no explicit "add user to project"
endpoint. The relationship is implicit in the existing data model.

Two prior sessions deferred this to the current slice:

- **Session 05 D1** (slice 5, Tickets): validated only the `role = DEVELOPER`
  half of spec 04 §4; left "active in that project" with a `TODO(slice-13)`
  marker in `TicketService.assertValidAssignee`.
- **Session 11 D3** (slice 11, CSV import): rows without `assigneeId` land with
  `null` instead of running an auto-assigner; same `TODO(slice-13)` in
  `CsvImportRowExecutor`.

Spec 12 §"Algorithm" point 1 actually proposes its own definition in the body:
> "Membership interpretation: any user who currently has at least one ticket in
> the project OR is the project owner."

So the spec writer pre-empts the ADR with a recommendation, but also flags
"Confirm with reviewer if possible." The decision below records that
confirmation and the precise edge cases.

## Options considered

1. **Explicit `project_members` join table.** Add a `ProjectMember(projectId,
   userId, joinedAt)` entity. Introduce `POST /projects/{id}/members` and
   `DELETE /projects/{id}/members/{userId}` admin endpoints. Membership is then
   a first-class concept that auto-assignment and assignee validation both
   join against.
2. **Spec-literal implicit definition.** Project membership = `{ project.ownerId }
   ∪ { u | ∃ ticket t : t.projectId = pid AND t.assigneeId = u.id }`. No table,
   no endpoints, no model change. Computed on read (a single JPQL or native
   query in `UserRepository`).
3. **Owner-only definition.** A project's "members" are just its owner. Spec 12
   never elaborates beyond owner.

## Decision

Option **(2)** — spec-literal implicit definition, intersected with `role =
DEVELOPER` for the auto-assignment path (per spec 12 §1 + spec §2 ADMIN
exclusion).

Formally, **the candidate set for auto-assignment on project `pid` is:**

```
candidates(pid) :=
  { u ∈ User |
      u.role = DEVELOPER
      ∧ u.deletedAt IS NULL          -- (vacuous today; future-proof)
      ∧ (
          u.id = projects[pid].ownerId
          OR ∃ t ∈ Ticket : t.projectId = pid ∧ t.assigneeId = u.id ∧ t.deletedAt IS NULL
        )
  }
```

The same set is the eligibility check for a manually-supplied `assigneeId`
on ticket create/update (closes Session 05 D1).

## Consequences

### Pros
- **Zero schema change.** No migration, no admin endpoints, no model bloat.
- **Spec-literal.** Reviewer comparing the spec to the code sees the same
  English sentence reflected in the JPQL.
- **Bootstrap case handled.** A brand-new project (zero tickets) still has at
  least one candidate (its owner, if the owner is a developer). Without the
  owner clause, the first ticket on a fresh project could never auto-assign —
  the algorithm would fall through to "no candidates → assigneeId = null"
  (spec §"Algorithm" point 4), which is correct per the spec but a bad UX.
- **Single query.** Auto-assigner + workload endpoint share the same JPQL,
  with the projection differing only by what the consumer wants
  (`pickOne` returns the top row's userId; `listAll` returns the projection
  rows directly).
- **Soft-deleted tickets are excluded automatically** via the existing
  `@SQLRestriction` on `Ticket` (slice 9) — so a developer who only ever
  had soft-deleted tickets in this project drops out of membership, which
  matches the spec's "currently has at least one ticket" phrasing.

### Trade-offs (and why they don't bite for this scope)
- **Membership flips as tickets move.** If a developer's only ticket in
  project P is reassigned away from them, they instantly stop being a member
  of P. For a take-home this is fine — the algorithm is invoked at
  ticket-create-time only, and the result is captured in the new ticket's
  `assigneeId`. For a real product with a "show me my projects" sidebar UI,
  you'd want explicit membership.
- **An ADMIN-owned project has only its non-owner developers as candidates**
  (because ADMIN owners are filtered by `role = DEVELOPER`). If no developer
  ever filed a ticket there, the auto-assigner falls through to
  `assigneeId = null` per spec §"Algorithm" point 4 — correct behavior, not
  a bug. The `/workload` endpoint will also return an empty list for such
  projects, which the consumer can interpret as "no team yet."
- **Owner-without-tickets edge case is special.** The owner is in the candidate
  set even with zero ticket history. This is the only asymmetry between the
  two halves of the OR clause. Documented; tested explicitly in
  `AutoAssignerTest.pick_ownerIsBootstrapMember`.
- **No "remove from project" semantic.** A developer who hasn't been involved
  in a project for months is still a member if they have any open or closed
  ticket there. Acceptable for the scope; mitigated by D2's ADMIN exclusion
  for the workload endpoint (busiest=lowest-count is still a sensible
  heuristic even if the membership set is wide).

## Migration path to option (1)

If a future deployment needs explicit membership:

1. Add `ProjectMember(projectId, userId, joinedAt, role)` entity + repo.
2. Backfill: for each existing `(projectId, assigneeId)` distinct pair, INSERT
   a member row; INSERT one for each `(projectId, ownerId)`.
3. Change `AutoAssigner.candidateIds(pid)` body to read from
   `project_members` instead of the JOIN.
4. Add `POST/DELETE /projects/{id}/members/{userId}` endpoints, gated by
   ADMIN or project owner.

The `AutoAssigner` interface + the `WorkloadService` shape are unchanged —
this ADR's choice is implementation-internal.

## Related

- Session 05 D1 (slice 5): `TicketService.assertValidAssignee` validated only
  the DEVELOPER half; this ADR's adoption tightens the remaining half.
- Session 11 D3 (slice 11): CSV import deferred auto-assignment; this ADR's
  adoption + Session 13 D3 closes the deferral.
- Spec 12 §"Algorithm" point 1 (the spec text that proposes this definition).
- ADR 0006 — RBAC on `/projects` (open reads) — keeps `/projects/{id}/workload`
  consistent with the open-read precedent for project resources.

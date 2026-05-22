# Spec 12 — Auto assignment by workload

## Trigger
Ticket **creation only** (NOT update) when `assigneeId` is absent in the request body.

## Algorithm
1. Find all users with role `DEVELOPER` who are members of the project. **Membership interpretation:** any user who currently has at least one ticket in the project OR is the project owner. (Documented in ADR `0004-project-membership.md`. Confirm with reviewer if possible.)
2. For each candidate, count `tickets WHERE status != 'DONE' AND deleted_at IS NULL AND assignee_id = user.id AND project_id = :pid`.
3. Pick the candidate with the lowest count. Tie-break: lowest `user.id` (i.e. oldest registration).
4. If no candidates exist for that project, set `assigneeId = null`. Do NOT raise an error.

## Endpoint
- `GET /projects/{projectId}/workload` → `[ { userId, username, openTicketCount } ]` sorted by `openTicketCount ASC`, then `userId ASC`.

## Acceptance criteria
1. Auto-assigned tickets have a non-null `assigneeId` after create, AND one audit row is written: `actor=SYSTEM, action=AUTO_ASSIGN`, `diff = { "assigneeId": <id> }`.
2. Workload endpoint excludes ADMIN users.
3. Workload endpoint returns 404 `PROJECT_NOT_FOUND` if the project is missing or soft-deleted.
4. Auto-assignment runs in the same `@Transactional` as ticket creation, so a failure rolls back both the ticket and the audit row.

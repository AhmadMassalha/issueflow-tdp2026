# Spec 04 — Tickets

## Endpoints
- `GET /tickets?projectId={id}`
- `GET /tickets/{ticketId}`
- `POST /tickets`
- `PATCH /tickets/{ticketId}`
- `DELETE /tickets/{ticketId}` (soft, see spec 08)
- Export/import — spec 10. Dependencies — spec 07. Soft-delete listing/restore — spec 08.

## Entity
| Field | Type | Constraints |
|---|---|---|
| id | BIGINT PK | |
| title | VARCHAR(200) | required |
| description | TEXT | optional |
| status | enum(TODO, IN_PROGRESS, IN_REVIEW, DONE) | required, default TODO, `@Enumerated(STRING)` |
| priority | enum(LOW, MEDIUM, HIGH, CRITICAL) | required, `@Enumerated(STRING)` |
| type | enum(BUG, FEATURE, TECHNICAL) | required, `@Enumerated(STRING)` |
| projectId | BIGINT FK | required |
| assigneeId | BIGINT FK nullable | |
| dueDate | TIMESTAMPTZ nullable | |
| isOverdue | BOOLEAN default false | set by escalation slice |
| version | BIGINT | JPA `@Version` |
| deletedAt | TIMESTAMPTZ nullable | soft-delete |
| createdAt / updatedAt | TIMESTAMPTZ | |

## Acceptance criteria

### Create
1. Missing required field → 400 with `details[]`.
2. Invalid enum value → 400 with `details[]` pointing to the field.
3. Project must exist and not be soft-deleted, else 404 `PROJECT_NOT_FOUND`.
4. If `assigneeId` omitted, auto-assignment runs (spec 12). If supplied but the user is not a DEVELOPER active in that project → 422 `INVALID_ASSIGNEE`.
5. Response includes all fields including `isOverdue: false` and `version: 0`.

### Update (PATCH)
6. Two concurrent PATCHes on the same ticket: the second is rejected with 409 `TICKET_VERSION_CONFLICT` (mapped from `ObjectOptimisticLockingFailureException`). Client must include `version`; missing → 400 `VERSION_REQUIRED`.
7. Status FSM — only `TODO → IN_PROGRESS → IN_REVIEW → DONE` (one step forward per request). Skipping levels and going backward → 409 `TICKET_INVALID_TRANSITION`.
8. Once `status = DONE`, any PATCH → 409 `TICKET_DONE_IS_IMMUTABLE`.
9. Transition to DONE is blocked while any blocker (spec 07) is not `DONE` → 409 `TICKET_HAS_OPEN_BLOCKERS`.
10. Manual priority change clears `isOverdue` (sets to false). The next escalation pass re-evaluates from the new priority — no special "skip" flag needed.

### Delete
11. Soft delete (slice 9). No hard-delete endpoint.

### List
12. `GET /tickets?projectId=…` excludes soft-deleted. Missing `projectId` → 400.

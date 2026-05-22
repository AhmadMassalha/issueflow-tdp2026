# Spec 06 — Audit log

## Endpoint
- `GET /audit-logs?entityType=&entityId=&action=&actor=` (all filters optional, AND-combined)

## Entity
| Field | Type |
|---|---|
| id | BIGINT PK |
| action | enum(CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE, LOGIN, LOGOUT) |
| entityType | enum(TICKET, PROJECT, USER, COMMENT, ATTACHMENT, DEPENDENCY) |
| entityId | BIGINT nullable |
| performedBy | BIGINT FK -> users, nullable |
| actor | enum(USER, SYSTEM) |
| diff | JSONB nullable (before/after snapshot, optional, stored as string for simplicity) |
| timestamp | TIMESTAMPTZ default `now()` |

## Acceptance criteria
1. Every state-changing service method writes one audit row in the **same `@Transactional`** as the change (call `auditLogService.log(...)` last; if the txn rolls back, the audit row rolls back too).
2. Auto-assign writes `actor=SYSTEM, action=AUTO_ASSIGN`.
3. Auto-escalation writes `actor=SYSTEM, action=AUTO_ESCALATE`.
4. `GET /audit-logs` is ADMIN-only → 403 otherwise.
5. Filters: invalid `entityType` / `action` / `actor` enum → 400.
6. Results sorted by `timestamp DESC`. Use JPA `Specification` to build the dynamic filter; cap with `Pageable` and return the standard pagination envelope.

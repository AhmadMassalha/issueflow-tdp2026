# ADR 0001 — Optimistic locking for Ticket and Comment

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** Ahmad Massalha

## Context
Spec 04 §6 and spec 05 §3 require that two users cannot concurrently update the same ticket or comment. JPA / Hibernate offers both optimistic (`@Version`) and pessimistic (`SELECT ... FOR UPDATE` via `LockModeType.PESSIMISTIC_WRITE`) strategies.

## Options considered
1. **Optimistic via `@Version`** — every read returns a `version`; on save Hibernate checks `WHERE id = ? AND version = ?`; mismatch → `ObjectOptimisticLockingFailureException` → HTTP 409.
2. **Pessimistic via `PESSIMISTIC_WRITE`** — each update acquires a row lock until commit.
3. **Manual CAS on `updatedAt`** — read-then-compare in the service.

## Decision
Optimistic locking (`@Version Long version` on `Ticket` and `Comment`).

Tickets and comments are rarely contended; the cost of a conflict (one user gets a 409 and retries) is low. Pessimistic locking would serialize updates across an HTTP request and risk lock-wait timeouts / deadlocks. Manual CAS would duplicate what Hibernate already does for free.

## Consequences
- Clients MUST echo back `version` in PATCH requests. Documented in `.cursor/rules/20-api-contract.mdc` and reinforced by 400 `VERSION_REQUIRED` when missing.
- `GlobalExceptionHandler` maps `ObjectOptimisticLockingFailureException` → 409 `TICKET_VERSION_CONFLICT` / `COMMENT_VERSION_CONFLICT` depending on the entity in the exception (or by mapping in each service to throw a typed domain exception).
- DTOs `UpdateTicketRequest` and `UpdateCommentRequest` include `version`.

# Spec 08 — Soft delete + restore

## Endpoints
- `GET /tickets/deleted?projectId={id}`   (ADMIN)
- `POST /tickets/{ticketId}/restore`      (ADMIN)
- `GET /projects/deleted`                 (ADMIN)
- `POST /projects/{projectId}/restore`    (ADMIN)

## Implementation
- `@SQLDelete(sql = "UPDATE tickets SET deleted_at = NOW() WHERE id = ?")` and `@SQLRestriction("deleted_at IS NULL")` on `Ticket`. Same shape on `Project`.
- Default JPA queries automatically exclude soft-deleted rows.
- `/deleted` listings use a dedicated repository method that bypasses the restriction — implement via `@Query(value = "SELECT t FROM Ticket t WHERE t.deletedAt IS NOT NULL AND t.projectId = :pid")` annotated with `@org.hibernate.annotations.SQLRestriction("")` override pattern, or with a separate native query. The simplest reliable approach: use a custom `@Query` with `nativeQuery = true` that filters `WHERE deleted_at IS NOT NULL`.
- Restore: set `deletedAt = null` and save. Done via a `@Modifying @Query` UPDATE to avoid Hibernate cascading effects.

## Acceptance criteria
1. `DELETE /tickets/{id}` and `DELETE /projects/{id}` set `deletedAt = NOW()`. Endpoints return 200 with no body.
2. Soft-deleted tickets do NOT appear in `GET /tickets`, `/tickets/export`, audit-log entity projections, workload counts, or mentions endpoints.
3. Soft-deleting a project does NOT cascade `deletedAt` to its tickets. Their `project` row is hidden by the filter, so they become orphaned-by-filter (still queryable via the admin-only `/deleted` endpoints). Documented in ADR `0002-soft-delete-cascade.md`.
4. Restoring a project leaves its tickets' `deletedAt` untouched.
5. Non-admin hitting `/deleted` or `/restore` → 403 `AUTH_FORBIDDEN`.
6. Restoring an already-active record → 409 `ALREADY_ACTIVE`.
7. Audit log: `DELETE` on soft-delete, `RESTORE` on restore.

# Spec 03 — Projects

## Endpoints
- `GET /projects`
- `GET /projects/{projectId}`
- `POST /projects`
- `PATCH /projects/{projectId}`
- `DELETE /projects/{projectId}` (soft delete — see spec 08)

## Entity
| Field | Type | Constraints |
|---|---|---|
| id | BIGINT, PK | |
| name | VARCHAR(128) | required, unique per owner |
| description | TEXT | optional |
| ownerId | BIGINT, FK -> users | required, must exist |
| deletedAt | TIMESTAMPTZ | nullable, used by soft-delete slice |
| createdAt / updatedAt | TIMESTAMPTZ | auto |

## Acceptance criteria
1. Create requires `name` and existing `ownerId`. Missing owner → 404 `USER_NOT_FOUND`. Missing name → 400.
2. Duplicate `(ownerId, name)` → 409 `PROJECT_DUPLICATE_NAME`.
3. `GET /projects` and `GET /projects/{id}` exclude soft-deleted (after slice 9, automatic via `@SQLRestriction`). Until then, all projects are returned.
4. `PATCH` may change `name` and/or `description` only. Other fields rejected via DTO not declaring them.
5. `DELETE` = soft delete after slice 9; until then, hard delete.
6. 404 `PROJECT_NOT_FOUND` on missing id.

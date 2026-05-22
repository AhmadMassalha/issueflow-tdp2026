# Spec 05 — Comments

## Endpoints
- `GET /tickets/{ticketId}/comments`
- `POST /tickets/{ticketId}/comments`
- `PATCH /tickets/{ticketId}/comments/{commentId}`
- `DELETE /tickets/{ticketId}/comments/{commentId}`

## Entity
| Field | Type | Constraints |
|---|---|---|
| id | BIGINT PK | |
| ticketId | BIGINT FK | required, must exist and not be soft-deleted |
| authorId | BIGINT FK -> users | required |
| content | TEXT | required, max 5000 chars |
| version | BIGINT | `@Version` |
| createdAt / updatedAt | TIMESTAMPTZ | |

## Acceptance criteria
1. Add: 200 with the created comment, including `mentionedUsers` (empty array until slice 10).
2. Get: returns array, newest first.
3. Update requires `version`. Concurrent update → 409 `COMMENT_VERSION_CONFLICT`. Missing `version` → 400 `VERSION_REQUIRED`.
4. Update re-runs the mention extractor (slice 10) in the same `@Transactional`.
5. Delete: hard delete (comments are NOT in soft-delete scope). 404 `COMMENT_NOT_FOUND` on missing.
6. Editing/deleting someone else's comment is allowed ONLY if the current user is ADMIN, else 403 `COMMENT_FORBIDDEN`. Enforced via `@PreAuthorize` + a service-side check against `comment.authorId == currentUser.id`.

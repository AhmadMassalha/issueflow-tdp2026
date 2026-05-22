# Spec 01 — Users

## Endpoints (from README)
- `GET /users`
- `GET /users/{userId}`
- `POST /users`
- `POST /users/update/{userId}`
- `DELETE /users/{userId}`

## Entity
| Field | Type | Constraints |
|---|---|---|
| id | BIGINT, PK, auto | |
| username | VARCHAR(64) | unique, required, `^[A-Za-z0-9_]{3,32}$` |
| email | VARCHAR(255) | unique, required, valid email |
| fullName | VARCHAR(128) | required |
| role | enum(ADMIN, DEVELOPER) | required, `@Enumerated(STRING)` |
| passwordHash | TEXT | required, BCrypt — `@JsonIgnore` (never returned) |
| createdAt | TIMESTAMPTZ | auto via `@CreatedDate` |
| updatedAt | TIMESTAMPTZ | auto via `@LastModifiedDate` |

## Acceptance criteria
1. Create user — 200 with body matching README; persisted; password hashed with BCrypt; `passwordHash` never appears in any response.
2. Duplicate username → 409 `USER_DUPLICATE_USERNAME`. Duplicate email → 409 `USER_DUPLICATE_EMAIL`. Map `DataIntegrityViolationException` from the unique constraint.
3. Invalid role (not ADMIN/DEVELOPER) → 400 `USER_INVALID_ROLE` (Jackson deserialization failure mapped explicitly).
4. Invalid email or username pattern → 400 with `details[]` listing the offending field.
5. `GET /users` returns array; `GET /users/{id}` returns object; 404 `USER_NOT_FOUND` if missing.
6. `POST /users/update/{userId}` updates `fullName` and `role` only; other fields silently ignored (DTO does not declare them).
7. `DELETE /users/{userId}` — hard delete (users are NOT in soft-delete scope per spec). 404 if missing.

## Out of scope (here)
- Password change endpoint.
- Self-service registration outside this controller. `/auth/login` lives in spec 02; there is no `/auth/register` endpoint per the README.

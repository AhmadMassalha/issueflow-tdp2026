# ADR 0005 — RBAC scope on `/users` endpoints

- **Status:** Accepted
- **Date:** 2026-05-23
- **Deciders:** Ahmad Massalha (Session 03 D2)

## Context

Spec 01 (Users) does not specify per-endpoint authorization. Spec 02 (Auth) §7
mandates RBAC via `@PreAuthorize("hasRole('ADMIN')")` on "ADMIN-only service
methods" but does not enumerate which `/users` methods qualify. Once slice 3
puts every endpoint behind authentication (spec 02 §3), some `/users`
operations need to be admin-gated, otherwise any developer could promote
themselves to ADMIN.

## Options considered

1. **Per-endpoint split:** `POST /users` (create), `POST /users/update/{id}`
   (edit), `DELETE /users/{id}` are ADMIN-only. `GET /users` and
   `GET /users/{id}` are open to any authenticated user.
2. **Lock the whole `/users` API to ADMIN.** Even reads.
3. **Defer all RBAC** — every authenticated user can do everything inside
   `/users`. Add restrictions later.

## Decision

Option **(1)**.

## Consequences

- Developers can see teammates so the UI can render assignee dropdowns,
  mention pickers, etc. without leaking management capabilities.
- Only admins can create, demote/promote, or delete users — privilege
  escalation requires an existing privilege.
- `AdminSeeder` (`CommandLineRunner`, see Session 03 D1) writes directly through
  `UserRepository` to bypass the `@PreAuthorize` annotation at boot time,
  because by definition no ADMIN principal exists yet on first run.
- If a future spec adds "users edit their own profile," that lives at a
  different endpoint (e.g. `POST /users/me`) so this RBAC stays uniform.
- Reads return enough to enumerate users (id, username, email, fullName, role,
  timestamps). `passwordHash` is omitted by the `UserResponse` DTO — see spec 01 §1.

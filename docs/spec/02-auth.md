# Spec 02 — Authentication (JWT)

## Endpoints
- `POST /auth/login` — body `{ username, password }` → `{ accessToken, tokenType: "Bearer", expiresIn: 3600 }`
- `POST /auth/logout` — invalidates current token
- `GET /auth/me` — returns current user profile (no `passwordHash`)

## Acceptance criteria
1. Login with valid creds returns 200 with a signed JWT (HS256, secret from `JWT_SECRET` env var, 1h expiry, payload `{ sub: userId, username, role, jti }`).
2. Login with bad creds → 401 `AUTH_INVALID_CREDENTIALS`. Same message and similar timing whether the username is unknown or the password mismatches (mitigates user enumeration).
3. Every endpoint except `/auth/login` requires a valid `Authorization: Bearer <jwt>` header. Configured via `SecurityFilterChain` with `permitAll()` only for `/auth/login` and `/error`.
4. Missing / expired / malformed token → 401 `AUTH_TOKEN_INVALID`.
5. Logout adds the token's `jti` to `TokenDenyListService` (in-memory `ConcurrentHashMap<String, Instant>` keyed by jti, value = token expiry). The filter rejects revoked jtis → 401 `AUTH_TOKEN_REVOKED`.
6. `GET /auth/me` returns the current user (looked up by `sub` claim).
7. RBAC: `@PreAuthorize("hasRole('ADMIN')")` on ADMIN-only service methods. `@EnableMethodSecurity(prePostEnabled = true)`. Non-admin → 403 `AUTH_FORBIDDEN` via `GlobalExceptionHandler` mapping `AccessDeniedException`.

## Implementation notes
- `JwtService` exposes `generate(User)`, `parse(String)`, `getJti(String)`.
- `JwtAuthenticationFilter` extends `OncePerRequestFilter`; runs before `UsernamePasswordAuthenticationFilter`.
- The deny-list interface (`TokenDenyList`) is abstract — see `docs/decisions/0003-token-denylist.md`.

## Non-functional
- The deny-list is in-memory and lost on restart — acceptable for the assignment; documented in ADR 0003.

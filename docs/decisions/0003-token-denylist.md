# ADR 0003 — In-memory JWT deny-list

- **Status:** Accepted (assignment scope)
- **Date:** 2026-05-22
- **Deciders:** Ahmad Massalha

## Context
Spec 02 §5: `POST /auth/logout` must invalidate the current token. JWTs are stateless; revocation requires either a deny-list or rotating short-lived tokens.

## Options considered
1. **In-memory deny-list** keyed by `jti`, value = token expiry; entries pruned after expiry. Cleared on app restart.
2. **Redis-backed deny-list** with TTL set to the remaining token lifetime.
3. **Short-lived access tokens + refresh tokens.** Heavier to implement; out of spec scope.
4. **Database deny-list** — adds DB round-trip on every authenticated request.

## Decision
Option 1, behind an `TokenDenyList` interface (`add(jti, expiry)`, `isRevoked(jti): boolean`, `pruneExpired()`), with an `InMemoryTokenDenyList` implementation backed by a `ConcurrentHashMap<String, Instant>` and a scheduled prune job.

## Consequences
- Revocations do NOT survive restarts — acceptable for the assignment.
- If a reviewer asks "how would you scale this horizontally?", the answer is "swap `InMemoryTokenDenyList` for a `RedisTokenDenyList`; the interface is already there, the filter doesn't change".
- One extra `@Scheduled` job (every 10 minutes) prunes expired entries to keep the map bounded.

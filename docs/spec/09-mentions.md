# Spec 09 — @Mention mechanism

## Endpoint
- `GET /users/{userId}/mentions?page=&pageSize=`

## Entity
`mention(id BIGINT PK, comment_id BIGINT FK, mentioned_user_id BIGINT FK, created_at TIMESTAMPTZ)` with `UNIQUE (comment_id, mentioned_user_id)`.

## Extraction rules
- Regex: `@([A-Za-z0-9_]{3,32})` (matches the username pattern from spec 01).
- Case-insensitive match against `users.username` (use `lower(username)` in the lookup query).
- Unknown usernames are silently ignored (no error raised).

## Acceptance criteria
1. On comment create — extract mentions, insert rows in the same `@Transactional`, return the comment with `mentionedUsers: [{ id, username, fullName }]`.
2. On comment update — diff old vs new mentions; insert new ones, delete removed ones; all in the same transaction as the content update.
3. `GET /users/{userId}/mentions` returns the standard paginated envelope (see `.cursor/rules/20-api-contract.mdc`), newest first by `mention.created_at`.
4. Each item in `data[]` is the full comment shape including `mentionedUsers`.
5. Page out of range returns empty `data[]` with correct `total`.
6. Mentioning yourself is allowed (no special case).

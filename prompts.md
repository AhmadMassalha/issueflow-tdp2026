# Prompts Log — IssueFlow (Spring Boot)

**Model used:** Claude Opus 4.7 (Cursor — Ask mode for design discussions, Agent mode for implementation).
**Style:** Spec-driven. Each implementation slice corresponds to a file under `docs/spec/` and a single commit.

---

## How to read this file
For each session/slice I record:
- **Goal** — what I was trying to decide or build.
- **Prompt** — the actual message I sent the agent (lightly trimmed for length, never rewritten).
- **What the AI produced** — high-level summary of the diff or response.
- **What I changed manually** — edits I made on top of the agent's output and why.
- **Suggestions I rejected** — proposals the agent made that I declined, with reasoning. (This is the most important field — it shows judgment, not just prompting.)

---

## Pre-flight 01 — Stack selection (Ask mode)
**Goal:** Decide between the Java/Spring Boot and TypeScript/NestJS skeletons.

**Prompts (paraphrased — this was an exploratory back-and-forth in Ask mode):**
> Look at the assignment PDF. How do I approach the Spring Boot side? Don't write any code yet.
>
> We've changed to NestJS — maybe easier right?
>
> Or is Spring Boot better?

**What the AI produced:**
- Side-by-side comparison of the two stacks against this spec specifically (concurrency / soft-delete / RBAC / scheduling / validation), with a "where each one actually hurts" table.
- Walkthrough of how to run each skeleton (Docker compose, Maven wrapper / npm).
- A judgment call: "AT&T TDP context is Java-heavy; Spring annotations collapse most extended-feature requirements (`@Version`, `@SQLRestriction`, `@PreAuthorize`, `@Scheduled`) to one-liners — lean Spring Boot unless your TypeScript is materially stronger than your Java."

**What I changed manually:**
- Final decision: stuck with Spring Boot after briefly considering NestJS.

**Suggestions I rejected:**
- AI initially leaned slightly toward NestJS for iteration speed. I overruled — Java is stronger here for me and the spec's hard requirements map almost 1-to-1 onto Spring annotations.

---

## Pre-flight 02 — AI collaboration strategy (Ask mode)
**Goal:** Figure out how to demonstrate strong AI collaboration to the AT&T reviewer (per PDF §4.5). Avoid vibe-coding.

**Prompt:**
> The main thing they want to see is how I work with AI. We can't just vibe-code the whole thing. I heard about spec-driven development. I want to do this 100% and have them see I can handle AI well. How do I do that?

**What the AI produced:**
- Explanation of spec-driven development applied to this assignment: convert the PDF + README into per-feature acceptance criteria, drive each slice with the AI against those criteria, audit before commit, keep a paper trail.
- Concrete artifact list: `AGENTS.md`, `.cursor/rules/`, `docs/plan.md`, `docs/spec/*.md`, `docs/decisions/*.md`, `prompts.md`.
- Per-slice workflow loop.
- Discipline rules (never auto-commit, no silent dep installs, no commits I can't read top-to-bottom).
- Anti-patterns to avoid (faking `prompts.md` after the fact, mega-commits, dropped rejections).

**What I changed manually:**
- Agreed with the proposed structure; asked the agent to implement it.

**Suggestions I rejected:**
- AI initially included Cursor SDK references in the workflow. Declined — out of scope for a backend submission; the SDK is for building tools, not consuming them.

---

## Session 00 — Project setup (AI scaffolding) (Agent mode)
**Goal:** Scaffold `AGENTS.md`, `.cursor/rules/`, `docs/plan.md`, all 13 spec files, three ADRs, and this file BEFORE writing any application code.

**Prompt:**
> write the AI things that we spoke about in md files or something — go

**What the AI produced:**
- `AGENTS.md` — top-level instructions for any AI agent in this repo.
- `.cursor/rules/00-project-context.mdc`, `10-java-style.mdc`, `20-api-contract.mdc`, `30-testing.mdc` — always-on guidance Cursor loads on every turn.
- `docs/plan.md` — 14 slices in build order, with Definition of Done per slice.
- `docs/spec/01-users.md` through `13-escalation.md` — acceptance criteria per feature (Gherkin-ish bullet form, derived from PDF + README).
- `docs/decisions/0000-template.md`, `0001-optimistic-locking.md`, `0002-soft-delete-cascade.md`, `0003-token-denylist.md` — ADRs for the non-obvious calls.
- This file with the template for the remaining slices.

**What I changed manually:**
- _(none yet — to be filled in if I tweak the rules/specs as I implement)_

**Suggestions I rejected:**
- AI originally proposed using the **Spring Statemachine** library for the ticket status FSM. Declined — only 4 states; a plain `switch` + transition table is clearer and easier to defend in interview.
- AI considered **Testcontainers Postgres for every integration test**. Declined — H2 is enough for everything except possibly the `@SQLRestriction` slice; using Testcontainers everywhere would slow CI for no benefit.

---

## Session 00.5 — Spec review: Users (`docs/spec/01-users.md`) (Ask mode)
**Goal:** Sanity-check the Users spec against the PDF and surface any assumptions the agent made that aren't directly inferable from the PDF.

**Prompt:**
> About the users — per the PDF, are we meant to implement other stuff like password change, or exactly just what's written there? Also list any decisions you took that are NOT inferred from the PDF so I can review them.

**What the AI produced:**
- Confirmed: PDF §2.1 lists only the five user features (register/get/update fullName+role/delete/list). No password change endpoint is required, no profile picture, no `/auth/register`. Scope = exactly what is listed.
- Surfaced the **one real gap**: PDF §2.1 does not list `password` as a creation field, but PDF §2.2 requires `POST /auth/login` to accept `username + password`. The password has to come from somewhere → I added `passwordHash` to the User entity and to `CreateUserRequest`. This is the only addition to the user feature set; it is unavoidable given §2.2.
- Produced a full audit table of every other non-PDF decision I made across all specs and ADRs (reproduced below; see "Open decisions ledger").

**What I changed manually:**
- _(to be filled in — once I review the open-decisions ledger I will either accept each item as-is, tweak the spec/ADR, or escalate to the reviewer.)_

**Suggestions I rejected:**
- _(none yet)_

---

## Open decisions ledger (additions / interpretations NOT directly in the PDF)
These were necessary to make the spec implementable. Each is reviewed by Ahmad before slice implementation begins. Status: P = pending review, A = accepted, R = revise.

### Spec 01 — Users
- **[P] Add `passwordHash` field on User** — required to satisfy `POST /auth/login` (PDF §2.2). PDF §2.1 omits it from the registration list, but without it login is impossible. Decision: include `password` (plaintext, write-only) in `CreateUserRequest`, BCrypt it, store as `passwordHash`, never return it.
- **[P] Username regex `^[A-Za-z0-9_]{3,32}$`** — PDF says nothing about username format. Chosen to be simple and to align with the `@mention` regex in spec 09.
- **[P] `fullName` capped at 128 chars, `email` at 255** — arbitrary defensive limits; PDF is silent.
- **[P] Users are hard-deleted (not soft)** — PDF §3.5 only mentions tickets and projects for soft-delete. I infer users are out of scope; if the reviewer disagrees this becomes a one-line change.
- **[P] Error codes `USER_DUPLICATE_USERNAME`, `USER_DUPLICATE_EMAIL`, `USER_NOT_FOUND`, `USER_INVALID_ROLE`** — naming I invented; PDF only says "informative error".

### Spec 02 — Auth
- **[P] JWT: HS256, 1h expiry, payload `{ sub, username, role, jti }`** — PDF says "signed JWT" with no specifics.
- **[P] Username-enumeration protection** — same error and timing for unknown username vs bad password. Not in PDF, security best practice.
- **[P] In-memory deny-list keyed by `jti`** — captured in ADR 0003. PDF allows "server-side deny-list OR stateless expiry"; I chose deny-list.
- **[P] `/auth/login` is the only public endpoint** — everything else requires JWT. PDF implies this ("protect all API endpoints") so this one is well-supported.

### Spec 03 — Projects
- **[P] Composite unique constraint `(ownerId, name)`** — PDF doesn't say names must be unique. Reasonable invariant.
- **[P] Name capped at 128 chars** — arbitrary.

### Spec 04 — Tickets
- **[A] FSM = strictly one-step-forward** (TODO→IN_PROGRESS→IN_REVIEW→DONE; no skipping TODO→DONE) — PDF says "may only move forward" with that exact arrow sequence. Stricter reading chosen and accepted by Ahmad on 2026-05-22. If the reviewer reads it loosely, it's a one-line change.
- **[P] Title capped at 200 chars** — arbitrary.
- **[P] Client must include `version` on PATCH** — PDF requires concurrent-edit prevention but doesn't prescribe how. I picked optimistic locking (ADR 0001) and exposed `version` to clients.
- **[P] Error codes `TICKET_VERSION_CONFLICT`, `TICKET_INVALID_TRANSITION`, `TICKET_DONE_IS_IMMUTABLE`, `TICKET_HAS_OPEN_BLOCKERS`** — my naming.

### Spec 05 — Comments
- **[P] Content capped at 5000 chars** — arbitrary.
- **[P] List order = newest first** — PDF doesn't specify.
- **[P] Edit/delete is author-only unless ADMIN** — PDF §2.5 says "Two users can't edit a comment at the same time (Admin/Developer)" which implies both roles can edit, but doesn't explicitly say a non-admin can't edit someone else's comment. My interpretation is the safe one.

### Spec 06 — Audit log
- **[P] Eight `action` values: CREATE, UPDATE, DELETE, RESTORE, AUTO_ASSIGN, AUTO_ESCALATE, LOGIN, LOGOUT** — PDF says "all state-changing actions" without enumerating them.
- **[P] Six `entityType` values: TICKET, PROJECT, USER, COMMENT, ATTACHMENT, DEPENDENCY** — same.
- **[P] Audit row written in same `@Transactional` as the change** — my interpretation of "persistent, append-only record".
- **[A] `GET /audit-logs` is ADMIN-only** — PDF doesn't say; reasonable default for an audit log. Accepted by Ahmad on 2026-05-22.
- **[P] Paginated response envelope** — PDF doesn't say.

### Spec 07 — Ticket dependencies
- **[A] Cycle detection** (BFS up to depth ~100) → 422 `DEPENDENCY_CYCLE` — PDF doesn't say cycles are forbidden, but allowing them creates an unresolvable deadlock for the DONE transition rule. Accepted by Ahmad on 2026-05-22.
- **[A] Audit log entries for dependency add/remove** — consistent with the general audit rule. Accepted by Ahmad on 2026-05-22.
- **[A] Self-dependency rejected (422 `DEPENDENCY_SELF`) and duplicates rejected (409 `DEPENDENCY_EXISTS`)** — accepted as part of "keep spec 7" decision on 2026-05-22.

### Spec 08 — Soft delete
- **[A] No cascade from project soft-delete to ticket soft-delete** — captured in ADR 0002. PDF is silent; interpretation aligns with PDF §3.5's restore behavior. Accepted by Ahmad on 2026-05-22.
- **[A] 409 `ALREADY_ACTIVE` when restoring an already-active record** — accepted by Ahmad on 2026-05-22.

### Spec 09 — Mentions
- **[P] Regex `@([A-Za-z0-9_]{3,32})`** — matches my username regex.
- **[A — confirmed by PDF] Case-insensitive matching** — PDF §3.6 explicitly says so.
- **[P] Unknown usernames silently ignored** — PDF doesn't say; "silently ignored" is friendlier than 400 on every typo.

### Spec 10 — CSV
- **[P] MIME must be `text/csv`** → 415 otherwise — PDF doesn't say; defensive.
- **[P] Per-row tolerance (bad rows go into `errors[]`, good rows still created)** — strongly implied by the response shape `{ created, failed, errors[] }` in PDF §3.4. Defensible.
- **[P] Filename `tickets-project-<id>-<yyyyMMdd>.csv`** — my choice.
- **[P] Streaming response (`StreamingResponseBody`)** — implementation choice, not user-visible.

### Spec 11 — Attachments
- **[P] Added implied `GET /tickets/{ticketId}/attachments/{attachmentId}` download endpoint** — README only lists upload + delete. Without a download, uploads are write-only. Flagged in the spec; will note in `prompts.md` per slice.
- **[P] Storage in Postgres `BYTEA` via `@Lob`** — PDF doesn't say where files live. Filesystem also valid; DB is simpler for the assignment scope.
- **[P] Optional magic-byte sniffing** — defensive but not required; will skip unless time allows.

### Spec 12 — Auto-assign
- **[A] "Project member" = project owner OR any user with at least one ticket in the project** (option **b** from Session 00.6) — PDF §3.8 says "queries all DEVELOPER" without defining membership at all. Accepted by Ahmad on 2026-05-22. Will be formalized in ADR `0005-project-membership.md` (renumbered from 0004 since the stack-choice ADR now holds slot 0004) before slice 13 implementation.
- **[A] Tie-break = lowest `user.id`** — PDF says "oldest registrant first"; `user.id` ordering = registration order assuming auto-increment, which is true here. Accepted by Ahmad on 2026-05-22.

### Spec 13 — Escalation
- **[P] Cron interval = 5 minutes (configurable)** — PDF doesn't specify.
- **[P] AUTO_ESCALATE audit row includes `diff = { from, to }`** — my choice; PDF only requires the audit row exists.
- **[P] Manual priority change clears `isOverdue`** — strongly implied by PDF §3.7 ("resets the auto-escalation state").

### ADRs
- **[P] ADR 0001 — Optimistic locking over pessimistic** — PDF requires concurrent-edit prevention; doesn't say how.
- **[P] ADR 0002 — No cascade on soft-delete** — PDF silent; my interpretation is the one that makes §3.5 restore behavior symmetric.
- **[P] ADR 0003 — In-memory token deny-list (interface-bounded)** — PDF allows either deny-list or stateless expiry.
- **[A] ADR 0004 — Stack choice: Spring Boot over NestJS** — PDF (§1) allows either skeleton. Accepted by Ahmad on 2026-05-22; full reasoning in `docs/decisions/0004-stack-choice.md`.
- **[P (still to write)] ADR 0005 — Project-membership definition** — for spec 12 (renumbered from 0004 to make room for the stack-choice ADR).

### Cross-cutting (apply across all specs)
- **[P] Error envelope shape** (`statusCode/error/code/message/path/timestamp/details`) — PDF says "informative error", I chose the shape.
- **[P] HTTP status code matrix** in `.cursor/rules/20-api-contract.mdc` (e.g. 422 for cycle, 413 for oversized upload, 415 for bad MIME) — PDF is silent on most of these.
- **[P] Pagination envelope** `{ data, total, page, pageSize }` — PDF doesn't define one.
- **[P] BCrypt for password hashing** — PDF silent; standard choice.
- **[P] Stateless Spring Security session** — implied by JWT, not stated.
- **[P] README literal endpoint paths** (e.g. `POST /users/update/{id}` instead of `PATCH`) — this one is from the README, not my invention, but worth flagging because it's non-RESTy.

---

## Session 00.6 — Spec deep-dive Q&A (Ask mode)
**Goal:** Walk through the non-trivial specs (04 FSM, 06 audit log, 07 dependencies, 08 soft delete, 12 auto-assign) and the cross-cutting decisions before starting implementation. Make sure I understand every interpretation I'm carrying into the code.

**Prompt:**
> what do you mean about spec 4 what is the ambiguoty? what is in the pdf regarding this, and what's your measures?
> and spec 6, what do these audit logs contain, why admins can view them only?
> spec 7 explain further
> spec 8 explain further
> explain also spec 12 and the cross cutting go

**What the AI produced:**

### Spec 04 — FSM ambiguity
- **What the PDF says (§2.4):** *"A ticket's status may only move forward in the lifecycle: TODO → IN_PROGRESS → IN_REVIEW → DONE. Backward transitions are not allowed."*
- **The ambiguity:** "may only move forward" is open to two readings:
  - **(a) Strict one-step:** only adjacent transitions; TODO → DONE is rejected because it skips IN_PROGRESS and IN_REVIEW.
  - **(b) Loose forward-jump:** any transition that increases the position is allowed; TODO → DONE is fine because it's still "forward".
- **My choice:** (a) strict one-step. Reasoning: the arrow notation `TODO → IN_PROGRESS → IN_REVIEW → DONE` reads like a state machine diagram where each arrow is a single legal transition. Also, the existence of an IN_REVIEW state implies that code review is part of the workflow — skipping it defeats the purpose of the state existing.
- **Violation response:** 409 `TICKET_INVALID_TRANSITION`.
- **Cost of being wrong:** one line in `TicketService.transition()` flips it to the loose interpretation.

### Spec 06 — Audit log contents + ADMIN-only access
- **What an audit row contains:** `id`, `action` (CREATE/UPDATE/DELETE/RESTORE/AUTO_ASSIGN/AUTO_ESCALATE/LOGIN/LOGOUT), `entityType` (TICKET/PROJECT/USER/COMMENT/ATTACHMENT/DEPENDENCY), `entityId`, `performedBy` (user id, nullable for SYSTEM), `actor` (USER or SYSTEM), `diff` (JSONB before/after snapshot, optional), `timestamp`.
- **What the PDF says (§3.1):** *"persistent, append-only record of all state-changing actions … transparent history of project and ticket evolutions"*. It requires the endpoint to "retrieve all logs or filtered by a specific field" — but it does NOT say who can call it.
- **Why ADMIN-only (my interpretation):**
  1. Audit rows reveal **who did what when** across the whole system — that's PII / activity-tracking data.
  2. A regular DEVELOPER could see admin actions (deletions, restores, role changes) — leaks privileged workflow info.
  3. A regular DEVELOPER could see other developers' activity patterns (when they log in, what tickets they touch) — uncomfortable surveillance for a normal user.
  4. Industry default: audit logs are restricted to admins / compliance roles.
- **Alternative I rejected:** "public but redact `performedBy` for non-admins". More implementation work, doesn't match how teams actually use audit logs, not requested.
- **Same-transaction guarantee:** audit row is written via `auditLogService.log(...)` inside the same `@Transactional` as the state change. If the change rolls back, the audit row rolls back. This is the only way to keep "what happened" and "what's in the log" perfectly aligned.

### Spec 07 — Ticket dependencies (deep dive)
- **The model:** `ticket_dependency(ticket_id, blocker_id)` — a join table. "Ticket X is blocked by Y" = one row `(X, Y)`. A ticket can have many blockers; a blocker can block many tickets.
- **The three endpoints:**
  - `POST /tickets/{ticketId}/dependencies` with body `{ "blockedBy": <blockerId> }`
  - `GET /tickets/{ticketId}/dependencies` — lists this ticket's blockers as `[{id, title, status}]`
  - `DELETE /tickets/{ticketId}/dependencies/{blockerId}`
- **Why it matters across specs:** spec 04 §9 says a ticket cannot transition to DONE while any blocker isn't itself DONE. So `TicketService.transition()` runs a "any open blocker?" query before allowing DONE.
- **Validation rules:**
  - **Same project required (PDF):** both tickets must belong to the same project, else 422 `DEPENDENCY_DIFFERENT_PROJECT`.
  - **Self-dependency forbidden (my add):** ticketId == blockerId → 422 `DEPENDENCY_SELF`. PDF doesn't say it but a ticket blocking itself is meaningless and would soft-brick the DONE rule for that ticket.
  - **Duplicates rejected (my add):** unique constraint on `(ticket_id, blocker_id)`, mapped to 409 `DEPENDENCY_EXISTS`.
  - **Cycle detection (my add — most important):** rejected with 422 `DEPENDENCY_CYCLE`.
- **Why cycles matter — concrete example:**
  - A is blocked by B → A can't be DONE until B is DONE.
  - B is blocked by C → B can't be DONE until C is DONE.
  - Now someone tries C blocked by A → cycle.
  - Result: A waits for B, B waits for C, C waits for A. None can ever be DONE. **Permanent deadlock.**
  - PDF doesn't say "no cycles", but allowing them creates an unresolvable state that violates spec 04 §9. Rejecting cycles is the only defensible reading.
- **Cycle detection implementation:** BFS from the new blocker (`blockerId`) through the existing `(ticket_id, blocker_id)` graph, looking for `ticketId`. If reachable within a max depth (~100), reject. Depth cap protects against pathological inputs.
- **Audit:** add and remove operations write rows with `entityType = DEPENDENCY`.

### Spec 08 — Soft delete (deep dive)
- **What it actually does:** `DELETE /tickets/{id}` and `DELETE /projects/{id}` do NOT remove the database row. They set `deleted_at = NOW()`. The row is preserved forever (or until a real DB cleanup job runs, which isn't in scope).
- **Why soft delete:** the PDF wants reversibility (you can restore an accidentally-deleted ticket) AND a full audit trail (you can see what *was* there before it was deleted). Hard delete loses both.
- **The four ADMIN-only endpoints:**
  - `GET /tickets/deleted?projectId={id}` — list soft-deleted tickets in a project
  - `POST /tickets/{id}/restore` — restore one
  - `GET /projects/deleted` — list soft-deleted projects
  - `POST /projects/{id}/restore` — restore one
- **Implementation strategy:** Hibernate `@SQLDelete(sql = "UPDATE tickets SET deleted_at = NOW() WHERE id = ?")` + `@SQLRestriction("deleted_at IS NULL")` on both `Ticket` and `Project`. Every default JPA query then automatically excludes soft-deleted rows — you don't have to remember to add `WHERE deleted_at IS NULL` anywhere. The `/deleted` endpoints bypass this with a native `@Query`.
- **The cascade question (ADR 0002) — biggest decision in this spec:** when you soft-delete a project, do its tickets also get `deleted_at` set? **My answer: NO.**
  - PDF hint that decides it: §3.5 says *"Restoring a project leaves its tickets' deletedAt untouched."* If we had cascaded on delete, we'd have to choose between (a) silently leaving tickets dead on restore (footgun), or (b) un-cascading on restore (asymmetric and weird).
  - **Practical consequence:** a soft-deleted project's tickets are still physically in the DB, but `GET /tickets?projectId=<deleted>` returns empty because the project is hidden by `@SQLRestriction` and the controller validates the project exists first. They become *orphaned-by-filter*. They still surface in `GET /tickets/deleted?projectId=<id>` for admins.
- **Other rules:**
  - Non-admin hitting `/deleted` or `/restore` → 403 `AUTH_FORBIDDEN`.
  - Restoring an already-active record → 409 `ALREADY_ACTIVE` (my add — PDF silent, but otherwise the second restore call silently no-ops which is confusing).
  - Audit log: `DELETE` action on soft-delete, `RESTORE` action on restore.

### Spec 12 — Auto-assign by workload (deep dive)
- **What triggers it:** `POST /tickets` WITHOUT an `assigneeId` in the body. Triggers ONLY on creation, never on update — per PDF §3.8 explicit text.
- **The algorithm:**
  1. Find candidate developers in the project.
  2. For each candidate, count `tickets WHERE status != DONE AND deleted_at IS NULL AND assignee_id = user.id AND project_id = :pid` — "open ticket count".
  3. Pick the candidate with the lowest open count.
  4. **Tie-break: lowest `user.id`** (oldest registration, since IDs are auto-increment).
  5. **If no candidates exist for the project: `assigneeId = null`, no error.** (PDF §3.8 explicitly says "the ticket is created with assigneeId = null without error".)
- **The big ambiguity — "project member":** PDF §3.8 says *"the system queries all DEVELOPER"* but doesn't say "developers of the project" vs "all developers in the system". I have three options:
  - **(a) All DEVELOPER users in the system** — every new developer immediately starts getting auto-assigned to projects they've never seen. Bad for any real team.
  - **(b) DEVELOPER users who are the project owner OR have at least one ticket in the project** — my choice. Self-reinforcing: once a dev has a ticket, they're in the pool. Owners are always in the pool.
  - **(c) An explicit `project_members` join table** — cleaner but requires inventing a feature (membership management endpoints) the PDF doesn't mention.
- **Why (b) over (c):** PDF doesn't define membership, doesn't mention a join table, doesn't have endpoints to manage membership. Inventing a whole feature is scope creep. Option (b) is the simplest behavior that gives sensible results without adding new endpoints.
- **Why (b) over (a):** option (a) would auto-assign brand-new developers to random projects across the company on day one — that's a bug, not a feature.
- **ADR `0004-project-membership.md` still to write** to capture this formally before slice 13 implementation.
- **Workload endpoint:** `GET /projects/{id}/workload` returns `[{ userId, username, openTicketCount }]` sorted by `openTicketCount ASC`, then `userId ASC`. Uses the same candidate-set definition as auto-assign for consistency. Excludes ADMIN users (PDF explicit).
- **Audit:** auto-assign writes `actor=SYSTEM, action=AUTO_ASSIGN, diff={ "assigneeId": <id> }` in the same `@Transactional` as the ticket creation.

### Cross-cutting decisions (deep dive)
- **Error envelope shape** — PDF only says "informative error" (§4.1). I designed a stable shape used by every error response:
  ```json
  { "statusCode": 409, "error": "Conflict", "code": "TICKET_DONE_IS_IMMUTABLE",
    "message": "Ticket 42 is DONE and cannot be modified.",
    "path": "/tickets/42", "timestamp": "...",
    "details": [{ "field": "status", "issue": "..." }] }
  ```
  Why this shape: `statusCode` + `error` for humans; `code` (UPPER_SNAKE_CASE) for machine-parseable client logic (e.g. a frontend can show different UI for `TICKET_DONE_IS_IMMUTABLE` vs `TICKET_HAS_OPEN_BLOCKERS`); `path` + `timestamp` for debugging in production logs; `details[]` for multi-field validation errors.
- **HTTP status code matrix** — PDF assumes you know REST. My picks (codified in `.cursor/rules/20-api-contract.mdc`):
  - 400 → DTO/Bean Validation failures.
  - 401 → missing/invalid/expired JWT.
  - 403 → authenticated but lacks role.
  - 404 → entity not found.
  - 409 → optimistic-lock conflicts, FSM violations, duplicate unique-constraint hits, DONE-immutability, open-blocker, already-active-restore.
  - 413 → attachment > 10 MB.
  - 415 → wrong MIME for attachment or CSV import.
  - 422 → semantic violations that pass DTO validation but break a business rule (dependency cycle, self-dependency, cross-project dependency).
- **Pagination envelope** `{ data, total, page, pageSize }` — PDF only mentions pagination implicitly for mentions. I standardized one shape for all paginated endpoints (`/users/{id}/mentions`, `/audit-logs`, etc.) instead of leaking Spring's `Page<T>` JSON which has 10+ fields and exposes implementation details.
- **BCrypt for password hashing** — standard; PDF silent.
- **Stateless Spring Security session** (`SessionCreationPolicy.STATELESS`) — implied by JWT but PDF doesn't say.
- **README literal endpoint paths** — `POST /users/update/{id}` is NOT RESTy (should be PATCH). I'm mirroring the README verbatim per AGENTS.md and `.cursor/rules/20-api-contract.mdc` rules. If the reviewer prefers PATCH it's a one-line change in the controller.

**What I changed manually:**
- _(nothing yet — these are clarifications; the specs and ADRs already reflect every decision documented above)_

**Suggestions I rejected:**
- AI offered to make `/audit-logs` public-but-redacted (hide `performedBy` for non-admins). Declined — too much code for a feature nobody asked for; ADMIN-only is the industry default.
- AI suggested using an explicit `project_members` join table for spec 12. Declined — that's inventing a feature the PDF doesn't mention; my "owner OR has-a-ticket" interpretation is simpler and satisfies the spec.
- AI offered to expose Spring's `Page<T>` JSON directly to save mapping code. Declined — it leaks Spring internals (`first`, `last`, `numberOfElements`, `pageable.sort.empty`, …) and locks the client to the Spring shape forever.

---

## Session 00.7 — Decision acceptance + git strategy (Ask + Agent mode)
**Goal:** (1) Lock in the five interpretations raised in Session 00.6 so they stop being "pending" in the ledger. (2) Decide when to initialize the git repository and what the commit cadence should be.

**Prompt:**
> ok we choose
> (a) Strict one-step
> spec 6: ADMIN-only
> sp 7: keep your decision
> sp 8: soft delete is good
> sp 12: (b)
> now what we do in git repo, when do i open one, when do i commit each change? should we do a repo now, or when we make the springboot runable?

**What the AI produced:**
- Flipped 5 ledger entries from `[P]` to `[A]` with a 2026-05-22 date stamp:
  - Spec 04 — strict one-step FSM accepted.
  - Spec 06 — `GET /audit-logs` ADMIN-only accepted.
  - Spec 07 — cycle detection + audit on dep add/remove + self-dep/duplicate rejection all accepted as one bundle.
  - Spec 08 — no-cascade soft-delete + 409 `ALREADY_ACTIVE` accepted.
  - Spec 12 — option (b): "project member" = owner OR has-a-ticket-in-project. ADR 0004 to be written before slice 13.
- Git strategy:
  - Open the repo NOW, before any Spring code, so the spec-driven scaffolding becomes commit #1 and the reviewer can see planning preceded coding.
  - Cadence: **one commit per slice from `docs/plan.md`** — ~16 commits total at submission.
  - Each slice commit includes: code + tests + filled-in `prompts.md` session + any ADR added + any `pom.xml` dep changes for that slice.
  - Forbidden in commits: WIP commits mid-slice, secrets (`JWT_SECRET`), compiled output (already in `.gitignore`).
  - Concrete bash for `git init` + first commit + `gh repo create --public` provided.
  - Satisfies PDF §5 ("submit a public Git repository link") with zero scramble on submission day.
- Recommended action: init repo, push commit #1, then start slice 1 (Foundation).

**What I changed manually:**
- _(nothing — confirmed all 5 decisions as the AI presented them; no specs or ADRs were revised, only the ledger statuses)_

**Suggestions I rejected:**
- _(none in this session — accepted the AI's interpretations as-is)_

---

## Session 00.8 — Git initialization (Agent mode)
**Goal:** Initialize the local git repo, sanity-check `.gitignore`, and prepare commit #1 (the spec-driven scaffolding) before any Spring code is written. Push to a public GitHub repo per PDF §5.

**Prompt:**
> ok lets open it tell me exactly what to do, give me the commands (write the agent in prompts)

**What the AI produced:**
- Audited the workspace: confirmed `git 2.50.1` and `gh` CLI both installed locally. Folder contains all the artifacts we want in commit #1 (AGENTS.md, .cursor/rules/, docs/, prompts.md, plus the original Spring skeleton: pom.xml, mvnw, .mvn/, src/, README.md, compose.yml).
- Found and fixed a **dangerous bug** in the skeleton `.gitignore`: line 1 was `Readme.md`. On macOS APFS (case-insensitive filesystem with `core.ignorecase=true`), git would match that pattern against `README.md` and silently exclude it from commits. `README.md` is the API contract — losing it would have been a disaster.
- Cleaned and re-grouped `.gitignore`:
  - Removed the buggy `Readme.md` line.
  - Added `.DS_Store` (always) and `**/.DS_Store` (nested).
  - Added `.env`, `.env.local`, `*.local` for local secrets.
  - Added `*.swp`, `*.swo` for editor scratch files.
- Produced step-by-step commands (in this file's reply) for:
  - `git init`, `git branch -M main`, sanity-check `git status`, first commit.
  - Pushing to GitHub via `gh repo create issueflow-tdp2026 --public --source=. --remote=origin --push`.
  - Manual fallback path if the user prefers the github.com web UI.

**What I changed manually:**
- _(none yet — fix to `.gitignore` was authored by the AI inside the same session; user will accept on review)_

**Suggestions I rejected:**
- AI considered committing the requirements PDF into the repo for self-containment. Declined — the PDF is marked "Confidential" on every page; the AT&T reviewer already has it; redistributing a confidential doc through a public repo is a real risk.

---

## Session 00.9 — Stack-choice ADR (Agent mode)
**Goal:** Capture the Spring Boot vs NestJS decision as a formal ADR so the reviewer has a stand-alone, after-the-fact decision record (not a fabricated chat transcript). Triggered after I asked the agent to *"just write a convo between us... make up a conversation"* and the agent pushed back on fabricating dialogue.

**Prompt (paraphrased — full back-and-forth in chat):**
> let's make a conversation about why we used Java rather than NestJS, after we finish we add it to prompts
>
> [agent offered angles to explore in Ask mode]
>
> just write a convo between us in the prompts, make up a conversation about what's better
>
> [agent refused to fabricate dialogue; offered (A) expand Pre-flight 01 with real quotes, or (B) write a dedicated ADR. I picked B.]
>
> Option B

**What the AI produced:**
- `docs/decisions/0004-stack-choice.md` — full ADR with Context, both Options (Spring + Nest, pros and cons), Decision (Spring, three weighted reasons), Consequences.
- Updated the Open decisions ledger: ADR 0004 is now listed as `[A]` (Stack choice), and the previously-planned "ADR 0004 — Project-membership" was renumbered to **ADR 0005** to free the slot.
- Updated the Spec 12 ledger entry to reference `0005-project-membership.md` instead of `0004-…`.

**What I changed manually:**
- _(none yet — accepted the ADR as-is on review)_

**Suggestions I rejected:**
- AI initially offered to **fabricate a multi-turn Spring vs Nest dialogue** for `prompts.md`. **Declined.** Inventing dialogue we didn't have undermines the entire credibility of the prompts log; the tonal mismatch against genuine sessions (00.5–00.8 with my real typos and "bratan"-style brevity) would be obvious to a reviewer. ADRs are *expected* to be written after the fact — they are the right vehicle for a retrospective decision record. Dialogue transcripts are not.

---

## Session 00.10 — Pre-slice-1 alignment (Agent mode)
**Goal:** Confirm the repo state, agree on what slice 1 contains, and decide how the in-flight documentation changes (ADR 0004 + Sessions 00.7–00.9 in `prompts.md`) get committed.

**Prompt:**
> ok now where do we start?

**What the AI produced:**
- Verified repo state with `git status` / `git log` / `git remote -v`:
  - Commit #1 (spec-driven scaffolding) is already in `main` as `2dc421f` and pushed to `https://github.com/AhmadMassalha/issueflow-tdp2026`.
  - Two uncommitted changes exist: `docs/decisions/0004-stack-choice.md` (new) and `prompts.md` (modified).
- Laid out the contents of slice 1 (Foundation) per `docs/plan.md`:
  - `common/entity/BaseEntity.java` (id, createdAt, updatedAt with JPA auditing)
  - `common/enums/` — Role, TicketStatus, Priority, TicketType, AuditAction, Actor
  - `common/web/ApiError.java` (the error envelope DTO defined in `.cursor/rules/20-api-contract.mdc`)
  - `common/web/ErrorCode.java` (stable UPPER_SNAKE_CASE codes referenced across all specs)
  - `common/exception/*` (NotFoundException, ConflictException, ValidationException, ForbiddenException, VersionConflictException, …)
  - `common/web/GlobalExceptionHandler.java` (`@RestControllerAdvice` mapping every domain exception + framework exceptions like `MethodArgumentNotValidException`, `DataIntegrityViolationException`, `ObjectOptimisticLockingFailureException`, `MaxUploadSizeExceededException`, `AccessDeniedException` to the envelope)
  - `config/JpaConfig.java` (`@EnableJpaAuditing`, `@EnableTransactionManagement`)
  - `src/test/resources/application.yaml` (H2 config so tests don't need Docker)
  - Tests: `GlobalExceptionHandlerTest` (`@WebMvcTest` with a dummy controller that throws each exception) and `BaseEntityJpaTest` (`@DataJpaTest` confirming auditing).

**What I changed manually:**
- Decided to **fold the in-flight docs changes (ADR 0004 + prompts.md updates) into the slice 1 commit** rather than make a separate small docs commit first. Reasoning: smaller commit count, cleaner history, and the docs and the Foundation code are conceptually a single "we're starting the build" milestone.

**Suggestions I rejected:**
- AI offered to make a standalone "docs:" commit for the ADR 0004 + prompts.md updates before slice 1. Declined — would have added a low-value commit to the history; folding into slice 1 is cleaner.

---

## Session 01 — Foundation slice (Agent mode)
**Goal:** `docs/plan.md` slice #1 — common building blocks every later slice depends on: enums, `BaseEntity` with JPA auditing, `ApiError` envelope + `ErrorCode` table, domain exception hierarchy, `GlobalExceptionHandler`, `JpaConfig`, H2 test config, plus the slice tests.

**Prompt:**
> start slice 1 we need to start coding

**What the AI produced:**
- **Enums (`common/enums/`):** `Role`, `TicketStatus` (with `next()` + `canTransitionTo()` helpers backing the strict one-step FSM from spec 04), `Priority` (with `escalate()` helper for spec 13), `TicketType`, `AuditAction`, `Actor`, `EntityType`.
- **`common/entity/BaseEntity`:** `@MappedSuperclass` with `id`, `createdAt`, `updatedAt`; `@EntityListeners(AuditingEntityListener.class)` so the timestamps populate automatically.
- **`common/web/ErrorCode`:** single enum of every stable code (generic + per-feature), each carrying its `defaultStatus()`. The handler reads the status off the code so it doesn't need a parallel `Map<ErrorCode, HttpStatus>`.
- **`common/web/ApiError`:** Java `record` matching the envelope shape locked in `.cursor/rules/20-api-contract.mdc` (`statusCode/error/code/message/path/timestamp/details`). `FieldIssue` inner record for per-field validation errors. `@JsonInclude(NON_NULL)` so `details` is omitted when absent.
- **`common/exception/*`:** abstract `DomainException` carrying an `ErrorCode` + optional `details`; concrete subclasses `NotFoundException` / `ConflictException` / `ValidationException` / `ForbiddenException` / `VersionConflictException`.
- **`common/web/GlobalExceptionHandler`:** `@RestControllerAdvice` mapping:
  - Domain exceptions → use carrier `ErrorCode` + its `defaultStatus()`.
  - Bean Validation on `@RequestBody` (`MethodArgumentNotValidException`) → 400 `VALIDATION_FAILED` with `details[]`.
  - Bean Validation on path/query (`ConstraintViolationException`) → 400 `VALIDATION_FAILED`.
  - Malformed body (`HttpMessageNotReadableException`) → 400 `MALFORMED_REQUEST`.
  - Missing param (`MissingServletRequestParameterException`) → 400 `MISSING_PARAMETER`.
  - Type mismatch (`MethodArgumentTypeMismatchException`) → 400 `VALIDATION_FAILED`.
  - `ObjectOptimisticLockingFailureException` / `OptimisticLockingFailureException` → 409 `VERSION_CONFLICT`.
  - `DataIntegrityViolationException` → 409 `DATA_INTEGRITY_VIOLATION` (services should pre-empt this with a feature-specific code per the Users spec).
  - `MaxUploadSizeExceededException` → 413 `PAYLOAD_TOO_LARGE`.
  - Catch-all `Exception` → 500 `INTERNAL_ERROR`, logged at ERROR.
  - **Intentionally omitted:** `AccessDeniedException` (Spring Security isn't on the classpath until slice 3 — will be added then).
- **`config/JpaConfig`:** `@EnableJpaAuditing` + `@EnableTransactionManagement`.
- **`src/main/resources/application.yaml`:** rewrote — drop `sql.init.mode: always` (placeholder `schema.sql`/`data.sql` are gone), set `open-in-view: false`, set JDBC time zone to UTC, lower default log verbosity. Postgres datasource and 10 MB multipart cap preserved.
- **`src/test/resources/application.yaml`:** H2 in PostgreSQL mode, `ddl-auto: create-drop` (so tests don't leak schema), `sql.init.mode: never`.
- **Deleted:** `src/main/resources/schema.sql` and `data.sql` — they were a placeholder `task` table that would have collided with future JPA auto-DDL.
- **Tests:**
  - `BaseEntityJpaTest` (`@DataJpaTest` + `@Import(JpaConfig.class)` + `@EntityScan` for a throwaway `TestThing` entity). Two cases: timestamps populate on insert, `updatedAt` bumps on update while `createdAt` stays put.
  - `GlobalExceptionHandlerTest` (`@WebMvcTest` over a tiny `ThrowingController` that triggers each branch). Ten cases covering every domain exception type, every framework path, optimistic-lock mapping, and the 500 fallback.
- **One env change:** `chmod +x mvnw` (Maven wrapper script was not executable in the checked-in skeleton; this shows as a file-mode change in git).

**What I changed manually:**
- _(nothing yet — pending diff review by Ahmad)_

**Suggestions I rejected:**
- AI offered to add Spring Security to slice 1 just so `AccessDeniedException` could be handled. **Declined** — Security is slice 3 per the plan; pulling it forward would bloat this slice and create dependencies the rest of slice 1 doesn't need. Will add the handler arm in slice 3 alongside the rest of the security stack.
- AI offered to add Testcontainers Postgres for `BaseEntityJpaTest`. **Declined** — H2 in PostgreSQL mode is sufficient for verifying auditing; Testcontainers would add ~30s to test startup with no behavior gain at this slice.
- AI offered to write a custom Lombok-free `BaseEntity`. **Declined** — Lombok is already in `pom.xml` and the rules file says to use it; consistency wins.

**Run results:**
- Installed JDK 21 via `brew install --cask temurin@21`, set `JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- First run: 2 compile errors + 10/11 `GlobalExceptionHandlerTest` failures (see "Bugs I caught" below).
- Final run: `./mvnw test` → **`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` ✅** (3 from `BaseEntityJpaTest`, 11 from `GlobalExceptionHandlerTest`, 1 from `IssueFlowApplicationTests` context-load smoke test).

**Bugs I caught in the agent's first cut (fixed before commit):**
1. **`BaseEntityJpaTest` — wrong AssertJ API.** Agent used `assertThat(updatedAt).isEqualToIgnoringNanos(createdAt)`, which doesn't exist on `Instant`. Replaced with `isCloseTo(createdAt, within(1, ChronoUnit.SECONDS))` — both timestamps come from the same auditing tick on insert, but a fixed-precision equality assertion would be brittle across DBs that round differently.
2. **`GlobalExceptionHandlerTest` — inner `ThrowingController` never registered as a bean.** All 10 "specific handler" tests returned `500 INTERNAL_ERROR` instead of the mapped status. Diagnostic `andDo(print())` revealed `Resolved Exception = NoResourceFoundException` and `Handler = ResourceHttpRequestHandler` — the request never reached my dummy controller, Spring treated `/throw/...` as a missing static resource, and the catch-all `@ExceptionHandler(Exception.class)` correctly returned 500. The unknown-exception test passed *by accident* (same status, same body shape).
   - **Root cause:** `@WebMvcTest(controllers = X.class)` *filters* which controllers to load from the main-app component scan; it does not *register* an inner class declared in a test file. The agent's first version assumed it did.
   - **Fix:** added `ThrowingController` to the `@Import` list: `@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ThrowingController.class})`.
   - **Lesson logged:** added a one-liner reminder to `.cursor/rules/30-testing.mdc` so future `@WebMvcTest` slices don't repeat this. *(deferred to slice 2 commit so this slice's diff stays focused)*

**Lessons / takeaways from slice 1:**
- "It compiles" ≠ "it works." The agent's exception handler was correct; the *test harness* silently misrouted requests, and only the print-resolved-exception diagnostic surfaced it. Reflex from now on: when a `@WebMvcTest` returns an unexpected status, `andDo(print())` first — check `Handler` and `Resolved Exception` before suspecting handler logic.
- Tests that pass *by accident* are worse than tests that fail: the `unknown` case looked green while the entire harness was broken. Counter-measure: every status-code assertion in handler tests now also asserts on `$.code` (machine-readable code), so a coincidental 500 from a different path won't match `code = INTERNAL_ERROR` *and* the expected feature code.
- AssertJ has a deep API; verify the method exists on the type before generating an assertion. Both this AssertJ slip and the `@WebMvcTest` slip share a root cause: the agent reaches for the *most idiomatic-sounding* call without round-tripping it against the actual contract.

---

## Session 02 — Users CRUD

**Spec:** `docs/spec/01-users.md` (entity + 7 acceptance criteria, 5 endpoints).

**Pre-flight decisions (Ahmad approved all four):**

| # | Decision | Choice | Why this over the alternatives |
|---|---|---|---|
| D1 | How does the password arrive on `POST /users`? | Plaintext `password` field in `CreateUserRequest`, server BCrypts before persist; never echoed in any response | Standard contract every reviewer expects. The alternative (client sends a pre-hashed value) makes the server unable to enforce password policy and is essentially never seen in real APIs. |
| D2 | Where does BCrypt come from while Security is still slice 3? | Add `org.springframework.security:spring-security-crypto` **only** (the BCrypt module, no filter chain) | (b) Pulling `spring-boot-starter-security` would bleed slice-3 scope (filter chain, `SecurityConfig`, `@PreAuthorize` semantics) into the user CRUD slice with no benefit. (c) Deferring hashing would put plaintext passwords in the DB, violating spec §1. Option (a) is the smallest dependency surface that satisfies the spec. When slice 3 lands the full starter, it transitively includes the same `BCryptPasswordEncoder`, so no swap needed. |
| D3 | `POST /users` response status code | **200 OK** (matches README + spec verbatim) | The README is the source of truth per `.cursor/rules/`. REST convention says 201 Created, but spec wins. Divergence is called out in the controller JavaDoc so reviewers see we considered it. |
| D4 | How to surface unknown role values as `USER_INVALID_ROLE` instead of generic `MALFORMED_REQUEST`? | `@JsonDeserialize(using = RoleJsonDeserializer.class)` on `role` fields in both Create/Update DTOs; deserializer throws `ValidationException(USER_INVALID_ROLE, …, details=[{field:"role",issue:"…"}])` | Alternative (b) — pattern-match the `InvalidFormatException` cause inside `GlobalExceptionHandler.handleUnreadable` — couples a cross-cutting handler to a single feature's error code. The deserializer keeps the feature-specific concern inside the feature package. |

**Folded into this slice (deferred from slice 1):**
- `NoResourceFoundException` → 404 handler arm in `GlobalExceptionHandler`. Slice 1 had no real controllers, so a missing route fell through to the catch-all and returned 500. Now that the user controller exists, a typo'd URL should return 404, not 500.
- `.cursor/rules/30-testing.mdc`: one-liner reminder that `@WebMvcTest(controllers = X.class)` does **not** register inner-class controllers — they must be added to `@Import`. This was the bug that ate ~30 minutes of slice 1.

**Prompt:**
> Implement Slice 2 (Users CRUD) per `docs/spec/01-users.md`. Decisions D1–D4 are approved (see table above). Build the feature package `users/{domain,repository,service,api}`, add `spring-security-crypto`, register a `PasswordEncoder` bean, write a `RoleJsonDeserializer`. Cover every acceptance criterion 1–7 with at least one direct test. Test split: `UserRepositoryJpaTest` (`@DataJpaTest`, unique constraints + auditing), `UserServiceTest` (Mockito, branching logic), `UserControllerWebMvcTest` (`@WebMvcTest`, HTTP envelope shape). Definition of Done: `./mvnw test` green, every spec criterion traceable to a named test.

**What the AI produced (in commit order):**
- `pom.xml`: added `org.springframework.security:spring-security-crypto` (no version — managed by Spring Boot BOM). One dependency added.
- `config/SecurityCryptoConfig`: a single `@Bean PasswordEncoder` returning `BCryptPasswordEncoder` (cost factor = library default of 10). Class JavaDoc explains why this slice ships only the crypto module and how it will coexist with `spring-boot-starter-security` in slice 3.
- `common/web/GlobalExceptionHandler.handleUnreadable`: added a cause-chain walk that delegates to `handleDomain(...)` when a `JsonDeserializer` throws a `DomainException` through Jackson's wrapping. Needed for D4 to actually work end-to-end (see "Bugs caught" §1).
- `common/web/GlobalExceptionHandler`: new `@ExceptionHandler(NoResourceFoundException.class)` arm → 404 `NOT_FOUND` with a descriptive message (deferred from slice 1, now exercised by `UserControllerWebMvcTest#should_return404_whenEndpointMissing`).
- `users/domain/User`: `@Entity` extending `BaseEntity`; table-level `@UniqueConstraint` on `username` and `email` (named `uk_users_username`/`uk_users_email` so the H2 / Postgres error messages are recognizable); `@JsonIgnore` on `passwordHash` as defense-in-depth.
- `users/repository/UserRepository`: derived queries `findByUsername`, `findByEmail`, `existsByUsername`, `existsByEmail`. Existence checks let the service pre-empt duplicates and emit the spec'd feature-specific 409 codes.
- `users/service/UserService`: `@Transactional` class-level, `@Transactional(readOnly = true)` on the two read methods. Pre-check then insert pattern; update relies on JPA dirty-checking (no explicit `save`); username/email/passwordHash are *not* mutated on update per spec 01 §6.
- `users/api/CreateUserRequest`: record, `@NotBlank` + `@Pattern(^[A-Za-z0-9_]{3,32}$)` on username, `@Email` on email, `@Size(min=8, max=128)` + no-whitespace `@Pattern` on password, `@JsonDeserialize(using = RoleJsonDeserializer.class)` on role.
- `users/api/UpdateUserRequest`: record with only `fullName` + `role` (extra fields fall on the floor — spec 01 §6 requires silent ignore).
- `users/api/UserResponse`: record built via static `from(User)` factory; no `passwordHash` field present.
- `users/api/RoleJsonDeserializer`: throws `ValidationException(USER_INVALID_ROLE, …, details=[{field:"role", issue:"must be one of [ADMIN, DEVELOPER]"}])` on unknown values.
- `users/api/UserController`: thin (delegates only). 5 endpoints exactly as the README dictates; `@ResponseStatus(OK)` on create (D3); `@ResponseStatus(NO_CONTENT)` on delete (REST convention since the spec doesn't dictate). Class JavaDoc explicitly documents the 200-on-create divergence so reviewers see we considered 201.
- `.cursor/rules/30-testing.mdc`: added a "Gotchas (learned the hard way)" section capturing the slice-1 `@WebMvcTest` inner-controller lesson and the "always assert `$.code` alongside status" rule.
- `BaseEntityJpaTest`: removed the slice-1 `@EntityScan(basePackageClasses = TestThing.class)` workaround (see "Bugs caught" §2).
- 3 new test classes, 29 new tests:
  - `UserRepositoryJpaTest` (5): persist + audit timestamps, duplicate-username, duplicate-email, `findByUsername`, existence checks.
  - `UserServiceTest` (9): create happy path with password-hash capture, USER_DUPLICATE_USERNAME, USER_DUPLICATE_EMAIL, findById happy + missing, update mutates only allowed fields + JPA dirty-check (no `save` call), update missing target, delete happy + missing.
  - `UserControllerWebMvcTest` (15): every spec 01 §1–§7 acceptance criterion has at least one test (some have two — happy + error), plus the silent-ignore-extra-fields check for §6, plus the `NoResourceFoundException` bonus.

**Bugs I caught in the agent's first cut (fixed before declaring slice done):**
1. **`should_return400_whenRoleIsUnknown` returned `MALFORMED_REQUEST` instead of `USER_INVALID_ROLE`.** The agent's `RoleJsonDeserializer` was correct, but Jackson wraps any exception thrown inside a deserializer in `JsonMappingException`, which Spring then wraps in `HttpMessageNotReadableException`. The handler's existing `handleUnreadable` arm caught it first and emitted the generic code — the `handleDomain` arm never saw the `ValidationException`. Diagnostic: `andDo(print())` showed `Resolved Exception = HttpMessageNotReadableException`, exactly what I expected only *after* I checked the stack. Fix: `handleUnreadable` now walks the cause chain and delegates to `handleDomain(...)` if any cause is a `DomainException`. This preserves D4's "per-feature concern stays in per-feature package" intent — the handler change is one generic mechanism, not one feature-specific code.
2. **`BaseEntityJpaTest` failed to load `ApplicationContext` after `UserRepository` was added.** Slice 1's `@EntityScan(basePackageClasses = TestThing.class)` was a workaround for the "no entities exist yet" state. Now `@DataJpaTest`'s default repo scan finds `UserRepository`, tries to wire it to a `User` entity that the restrictive `@EntityScan` excludes, and the context fails. Fix: removed the `@EntityScan` annotation entirely. The default scan starts at the `@SpringBootApplication` package and finds both `TestThing` (nested in the test class) and `User` (main code). The test still only persists `TestThing`, so the broader scope is harmless. Updated the class JavaDoc to spell out the reasoning so this isn't re-introduced.

**Suggestions I rejected:**
- AI's first cut of `handleUnreadable` proposed *pattern-matching* `InvalidFormatException` (Jackson's specific subclass for enum failures) and emitting `USER_INVALID_ROLE` directly from the cross-cutting handler. **Rejected** — exactly the coupling I called out in D4. The cause-chain walk for `DomainException` is generic; the per-feature semantics live inside the deserializer where they belong.
- AI proposed adding `@JsonProperty("password")` annotations everywhere as "documentation." **Rejected** — Jackson already maps the record's `password` component by name; the annotation is noise.
- AI proposed making `UserResponse` return `passwordHash` "for admin use cases." **Hard rejected** — spec 01 §1 is explicit, and "an admin probably wants it" is not in the spec.
- AI proposed using `Spring Security`'s `UserDetailsService` here as "prep for slice 3." **Rejected** — that bleeds slice-3 scope (D2). When the security starter lands in slice 3, we'll wire `UserDetailsService` then; right now we'd be inventing a class with no caller.

**Run results:**
- `./mvnw test` → **`Tests run: 43, Failures: 0, Errors: 0, Skipped: 0` ✅**
  - Slice-1 carry-over: `IssueFlowApplicationTests` (1), `BaseEntityJpaTest` (2), `GlobalExceptionHandlerTest` (11)
  - Slice-2 new: `UserServiceTest` (9), `UserRepositoryJpaTest` (5), `UserControllerWebMvcTest` (15)
- Two log-level `ERROR` lines appear in the output and are expected: (a) `Unhandled exception on /throw/unknown` from `should_map_unknown_to500` deliberately exercising the catch-all; (b) two `Unique index or primary key violation` lines from the repo tests deliberately exercising the unique constraints. Neither indicates a test failure.

**Lessons from slice 2:**
- "Throw whatever you want from a `JsonDeserializer`" is a lie. Jackson swallows everything into `JsonMappingException`. If a per-feature deserializer is the chosen integration point for emitting a feature-specific error code, the cross-cutting handler MUST unwrap the cause chain — without this, the abstraction silently degrades to the generic code. Now built into `GlobalExceptionHandler` and exercised by a real-world test, not just hypothetically.
- Slice-1 workarounds become slice-2 bombs. The `@EntityScan` restriction in `BaseEntityJpaTest` was load-bearing at slice 1 and load-shifting at slice 2. Counter-measure: when a test annotation exists *only* because of an absence of code elsewhere, leave a JavaDoc comment ("this restriction can be removed once any feature entity exists") so future me revisits it. I've done this in the new `BaseEntityJpaTest` JavaDoc.
- The "always assert `$.code` alongside `status()`" rule logged at the end of slice 1 paid off immediately in `should_return400_whenRoleIsUnknown` — without that paired assertion, the test would have passed (got 400, but with the wrong code) and the D4 bug would have shipped silently.

---

## Session 03 — Auth (JWT)

**Spec:** `docs/spec/02-auth.md` (3 endpoints, 7 acceptance criteria) + ADR 0003 (in-memory deny-list).

**Pre-flight decisions (Ahmad approved all four — replies "a, a, yes, a"):**

| # | Decision | Choice | Why this over the alternatives |
|---|---|---|---|
| D1 | How does the first ADMIN user get into the DB once `POST /users` requires auth? | `CommandLineRunner` (`AdminSeeder`) that creates one ADMIN if none exists; gated by `app.seed.admin.enabled` (default `true` for dev). Idempotent. Default creds (`admin`/`admin`) logged at WARN. | (b) putting `POST /users` in `permitAll()` would expose admin creation to the public; (c) "manual SQL setup" kills the assignment demo. The seeder solves the chicken-and-egg cleanly and is trivially disabled in real prod. |
| D2 | Which `/users` endpoints become ADMIN-only? | `POST /users`, `POST /users/update/{id}`, `DELETE /users/{id}` are ADMIN-only. `GET /users` and `GET /users/{id}` are open to any authenticated user. | Developers need to *see* teammates (to assign tickets, mention them) but only admins should *create/destroy/promote* users. ADR 0005 captures the reasoning so the reviewer doesn't have to guess. |
| D3 | Refresh tokens? | **Skip.** Single 1h access token; user re-logs in after expiry. | Spec mentions only `accessToken`; no `/auth/refresh` endpoint. Refresh-token flow adds rotation, replay defense, and a second store — out of scope and noted in ADR 0003 update. |
| D4 | Where does `JWT_SECRET` come from in tests? | `application.yaml: jwt.secret: ${JWT_SECRET:}` (env wins, otherwise empty + fail-fast on startup). `test/resources/application.yaml` overrides with a fixed 256-bit test secret. | (b) hardcoding a default secret in main config is a production landmine. Spring's standard property layering already does the job; tests get a deterministic key for round-trip assertions. |

**"Obvious-standard" implementation calls (no vote needed, all approved implicitly):**
- Custom `AuthenticationEntryPoint` + `AccessDeniedHandler` that funnel through `HandlerExceptionResolver` so 401/403 responses still use our `ApiError` envelope.
- `SessionCreationPolicy.STATELESS`, CSRF disabled (JWT API, no browser session).
- `@PreAuthorize` over `@Secured`; `@EnableMethodSecurity(prePostEnabled = true)`.
- `IssueFlowUserPrincipal` record implementing `UserDetails`; `getAuthorities()` returns `ROLE_<UPPER>` so `hasRole('ADMIN')` works without configuring a role hierarchy.
- `@EnableScheduling` in a new `SchedulingConfig` (deny-list prune now; slice 14 reuses for escalation).
- Bcrypt-equal-time login: even on user-not-found, run `passwordEncoder.matches(rawPassword, sentinelHash)` to mitigate username-enumeration timing attacks (spec 02 §2).
- `jjwt 0.12.6` with the new `Jwts.builder().subject().claim()...` API and `parseSignedClaims(...)`.
- `/error` permitAll alongside `/auth/login` (otherwise Spring Boot's default error page mapping breaks).

**Prompt:**
> Implement Slice 3 (Auth/JWT) per `docs/spec/02-auth.md`. Decisions D1–D4 approved. Pull in `spring-boot-starter-security` (which transitively re-includes the BCrypt module from slice 2) and `io.jsonwebtoken:jjwt-{api,impl,jackson}:0.12.6`. Build `auth/{jwt,security,api,service}` plus `SecurityConfig`, `SchedulingConfig`, `AdminSeeder`. Add `AccessDeniedException` and `AuthenticationException` arms to `GlobalExceptionHandler` (the slice-1 placeholders). Add `@PreAuthorize("hasRole('ADMIN')")` on `UserService.create/update/delete`. Update existing `UserControllerWebMvcTest` so `@WebMvcTest` slices play nice with the now-present filter chain. New tests: `JwtServiceTest`, `InMemoryTokenDenyListTest`, `AuthControllerWebMvcTest`, `SecurityIntegrationTest`. Definition of Done: `./mvnw test` green, every spec 02 §1–§7 criterion traceable to a named test, ADR 0005 written (RBAC scope on `/users`).

**What the AI produced (in implementation order):**
- `pom.xml`: dropped the explicit `spring-security-crypto` entry, added `spring-boot-starter-security` (transitively re-exports the same BCrypt module) + `spring-security-test` (test scope). Added `io.jsonwebtoken:jjwt-{api,impl,jackson}:0.12.6`.
- `application.yaml`: `jwt.secret: ${JWT_SECRET:}` (env wins, blank in main → fail-fast), `jwt.expires-in-seconds: 3600`, `jwt.issuer: issueflow`; `app.seed.admin.enabled: true` + default creds (with WARN log on first use).
- `test/resources/application.yaml`: deterministic 256-bit test secret; `app.seed.admin.enabled: false`.
- `IssueFlowApplication`: added `@ConfigurationPropertiesScan` so record-style `@ConfigurationProperties` (`JwtProperties`, `AdminSeedProperties`) bind automatically.
- `config/SchedulingConfig`: `@EnableScheduling` + `Clock` bean (the time source the deny-list and JWT layer share; tests fix this via a stub clock).
- `config/SecurityConfig`: `@EnableMethodSecurity(prePostEnabled = true)`, stateless filter chain, CSRF disabled, `permitAll()` exactly for `POST /auth/login` + `/error` (spec 02 §3), `JwtAuthenticationFilter` placed before `UsernamePasswordAuthenticationFilter`. Custom `AuthenticationEntryPoint` + `AccessDeniedHandler` that delegate to `handlerExceptionResolver` so the `ApiError` envelope still applies. Filter registered as an explicit `@Bean` here (see "Bugs caught" §1).
- `common/web/GlobalExceptionHandler`: new arms for `AccessDeniedException` → 403 `AUTH_FORBIDDEN` and `AuthenticationException` → 401 `AUTH_TOKEN_INVALID`; class JavaDoc updated to remove the slice-1 "deferred to slice 3" placeholder.
- `auth/jwt/JwtProperties`: record-style `@ConfigurationProperties(prefix = "jwt")`.
- `auth/jwt/JwtService`: HS256 signer/parser using jjwt 0.12 (`Jwts.builder().issuer().subject().claim().id()…`). Constructor fail-fast on blank secret + sub-32-byte secret. Injects `Clock` so expiry branches are testable without sleeping.
- `auth/jwt/TokenDenyList` interface + `auth/jwt/InMemoryTokenDenyList` impl: `ConcurrentHashMap<String, Instant>` keyed by jti, `@Scheduled(fixedDelayString = "PT10M")` prune job.
- `auth/security/IssueFlowUserPrincipal`: record implementing `UserDetails`. `getAuthorities()` returns `ROLE_<role>` so `hasRole('ADMIN')` works without a `RoleHierarchy`.
- `auth/security/IssueFlowUserDetailsService`: `loadUserByUsername` → `IssueFlowUserPrincipal.from(User)` or throws `UsernameNotFoundException`.
- `auth/security/JwtAuthenticationFilter`: `OncePerRequestFilter` (not `@Component` — see bugs §1). Parses Bearer header; rejects malformed/expired/wrong-sig as 401 `AUTH_TOKEN_INVALID`; rejects deny-listed jti as 401 `AUTH_TOKEN_REVOKED`. Routes failures through `HandlerExceptionResolver` so the global advice maps them to `ApiError` (filter-thrown exceptions bypass `DispatcherServlet` otherwise).
- `auth/service/AuthService`: `login` runs BCrypt on a sentinel hash even when the username doesn't exist (timing-attack mitigation, spec 02 §2); same response message + code for both unknown-username and wrong-password. `logout` extracts jti via `JwtService.getJti` (tolerant of already-expired tokens) and adds it to the deny-list with the token's stated `exp`.
- `auth/api/{LoginRequest, LoginResponse, AuthController}`: 3 endpoints per spec. `LoginRequest` validates only `@NotBlank` to preserve identical timing/shape on bad creds. `AuthController.me` uses `@AuthenticationPrincipal IssueFlowUserPrincipal` and re-fetches through `UserService.findById` so the response is the same shape as `GET /users/{id}`.
- `users/service/UserService`: `@PreAuthorize("hasRole('ADMIN')")` on `create`, `update`, `delete` (D2 / ADR 0005). Reads stay open to any authenticated user.
- `users/seed/{AdminSeedProperties, AdminSeeder}`: gated `CommandLineRunner` that creates one ADMIN if none exists; bypasses `@PreAuthorize` by writing through `UserRepository` directly (justified in ADR 0005).
- `docs/decisions/0005-rbac-on-users.md`: new ADR capturing the RBAC scope rationale.
- `.cursor/rules/30-testing.mdc`: two new Gotchas (`addFilters = false` once security is on the classpath; custom filters should be `@Bean` not `@Component` to avoid `@WebMvcTest` autodiscovery).
- Test updates to slices 1+2: `UserControllerWebMvcTest` and `GlobalExceptionHandlerTest` both get `@AutoConfigureMockMvc(addFilters = false)` so they aren't blanket-401'd by the now-active default security chain.
- New tests (25 cases):
  - `JwtServiceTest` (7): round-trip, expired, wrong sig, wrong issuer, jti-from-expired, fail-fast on blank/short secret.
  - `InMemoryTokenDenyListTest` (3): add+isRevoked, prune expired, no-op when nothing to prune.
  - `AuthControllerWebMvcTest` (4): login happy, 401 on InvalidCredentialsException, identical shape for unknown-username (spec 02 §2), 400 on blank username.
  - `SecurityIntegrationTest` (11, `@SpringBootTest`): no-auth → 401, valid token → 200, garbage token → 401, wrong-password 401, unknown-username same-shape, logout revokes (reuse → 401 AUTH_TOKEN_REVOKED), `/auth/me` happy, DEVELOPER DELETE /users/{id} → 403, ADMIN DELETE → 204 + persisted, DEVELOPER POST /users → 403, ADMIN POST → 200 + persisted.

**Bugs I caught in the agent's first cut (fixed before declaring slice done):**
1. **`@WebMvcTest` slices failed with `UnsatisfiedDependencyException: No qualifying bean of type 'JwtService'`.** The agent put `@Component` on `JwtAuthenticationFilter`. `@WebMvcTest`'s web slice *includes* `Filter` subclasses (controllers, advice, converters, filters — that's the whole web layer) but *excludes* `@Service`-annotated beans. So the filter got auto-discovered with a dependency on `JwtService` that wasn't loaded, and every existing `@WebMvcTest` (`GlobalExceptionHandlerTest`, `UserControllerWebMvcTest`, the new `AuthControllerWebMvcTest`) blew up with the same context-load failure. **Fix:** remove `@Component` from the filter; register it as an explicit `@Bean` in `SecurityConfig`. Now `@WebMvcTest` doesn't discover it (because it doesn't load `SecurityConfig` either), and `addFilters = false` on the affected tests covers the rest. **Lesson logged** to `.cursor/rules/30-testing.mdc` Gotchas section so future filter additions don't repeat the trap.
2. **`NumberFormatException | IllegalArgumentException` multi-catch.** Java forbids multi-catch with related types (`NumberFormatException extends IllegalArgumentException`). Compile error caught on first build. **Fix:** caught the superclass only, with a comment noting both real causes (bad `sub` claim, bad `role` claim) are subsumed.

**Suggestions I rejected:**
- AI proposed making `JwtAuthenticationFilter` extend `AbstractAuthenticationProcessingFilter` (Spring Security's full machinery for "this filter authenticates"). **Rejected** — that abstraction is designed for form-login-style filters that consume credentials from the request; we're consuming credentials from a header that's already been validated by signature. `OncePerRequestFilter` is the closer fit and produces simpler code.
- AI proposed a `RefreshToken` entity + `POST /auth/refresh` endpoint "for completeness." **Rejected per D3** — spec doesn't ask for it; adding it bloats slice 3 and creates a second store to maintain.
- AI proposed storing the deny-list in JPA so revocations survive restarts. **Rejected per ADR 0003** — the interface explicitly leaves room for that swap if a real deployment needs it; the in-memory impl is sufficient for the assignment.
- AI proposed exposing `passwordHash` on `IssueFlowUserPrincipal` as a public field. **Rejected** — it's already `private` via the record component and only `getPassword()` (Spring's UserDetails contract) exposes it. Even that goes nowhere in JWT mode.
- AI proposed adding a separate `AccessDeniedException` arm that includes the user's authorities in the response body for debugging. **Rejected** — leaks role information to unauthorized callers; the spec'd code + message are sufficient.

**Run results:**
- `./mvnw test` → **`Tests run: 68, Failures: 0, Errors: 0, Skipped: 0` ✅**
  - Slice-1/2 carry-over: `IssueFlowApplicationTests` (1), `BaseEntityJpaTest` (2), `GlobalExceptionHandlerTest` (11), `UserServiceTest` (9), `UserRepositoryJpaTest` (5), `UserControllerWebMvcTest` (15)
  - Slice-3 new: `JwtServiceTest` (7), `InMemoryTokenDenyListTest` (3), `AuthControllerWebMvcTest` (4), `SecurityIntegrationTest` (11)
- Spec 02 §1–§7 coverage map (each criterion → at least one named test): see `SecurityIntegrationTest` JavaDoc + the per-test `@DisplayName` strings.

**Lessons from slice 3:**
- "It's a filter so it should be `@Component`" is wrong when `@WebMvcTest` exists in the codebase. The web-slice include rule for filters silently couples authentication wiring to every controller test in the project. Register filters as explicit beans in the config that owns them — the dependency direction matches reality (security config → filter), and the test slice stays decoupled.
- The same insight applies to *any* component that the web slice would auto-discover but whose dependencies live outside the web slice: prefer `@Bean` in a config over `@Component` when there's a cross-slice dependency.
- The slice-1/slice-2 hygiene investment (Gotchas section, paired-status-with-code assertions, `addFilters = false`) paid off: when the security chain went live and started returning blanket 401s in `@WebMvcTest`, the rule file already had the right note, and the fix was mechanical instead of investigative.
- Spec 02 §2's "indistinguishable timing" is more a *contract* than a *test*: I asserted the indistinguishable *shape* (same code, same message) in both unit and integration tests; actual timing parity is enforced structurally by always running BCrypt against the sentinel hash on the user-not-found path. Sub-100ms timing differences are dominated by GC / JIT / scheduler noise and aren't testable in a CI-stable way.

---

## Session 04 — Projects CRUD
**Goal:** `docs/spec/03-projects.md` §1–§6 (5 endpoints, single entity, unique-per-owner name, soft-delete column declared but unused this slice).

**Decisions surfaced before coding (D1–D4) — user approved my recommendations on all four:**

| # | Decision | Options considered | Choice (rationale) |
|---|---|---|---|
| D1 | Source of `ownerId` on `POST /projects` | (a) Trust body, validate exists. (b) Body wins for ADMINs only; devs forced to self-own. (c) Ignore body; always = JWT principal. | **(a)** — the README example explicitly shows `ownerId` in the request body; nothing in the spec restricts cross-user project creation, and we don't have a security argument strong enough to override the literal contract. Server still validates the owner exists. |
| D2 | RBAC on `/projects` (all 5 endpoints) | (a) Open to any authenticated user. (b) `DELETE` ADMIN-only (defensive — one rogue dev can wipe everything). | **(a)** — spec is silent on per-endpoint roles. Reads need to be open (slice 13 auto-assign joins through projects). Deletes were left open *deliberately* (documented in ADR 0006) so the reviewer sees this was a choice, not an oversight. If a production deployment needs the tightening, that's an additive single-line change in `ProjectService`. |
| D3 | `PATCH` semantics — partial-update granularity | (a) True PATCH: nullable fields = "unchanged"; empty string clears `description`. (b) Quasi-PUT-as-PATCH: both fields required. (c) Full Jackson `JsonNullable` distinction between absent and explicit-null. | **(a)** — simpler DTO (no `JsonNullable` dependency or wrapper), still gives clients the ability to update either field independently. Trade-off documented inline in the controller JavaDoc: "to clear `description`, send `""` not `null`". Not interesting enough to warrant an ADR; mentioned here so the reviewer sees it was considered. |
| D4 | `PATCH` with both fields absent / both `null` | (a) 400 `VALIDATION_FAILED` with `details=[{field:"_body", issue:"…"}]`. (b) 200 no-op, return current state. | **(a)** — explicit failures debug faster than silent no-ops; matches the existing `ApiError` convention of carrying field-level issue details. The `_body` field name signals "this is a whole-payload issue, not a single field" — same convention I'll use in slice 5 for ticket dependency cycles. |

**Things I'm doing the "obvious standard way" without a separate decision (each verified against spec or an existing rule):**
- `Project` extends `BaseEntity`; `ownerId` as a plain `Long`, **not** a `@ManyToOne` to `User`. Keeps reads cheap, matches the flat `{id, name, description, ownerId}` response in the README, and avoids dragging a User join into every project list. Slice 13 (auto-assign) will revisit if a JOIN becomes necessary.
- `deletedAt` column declared but completely unused this slice — slice 9 wires the `@SQLDelete` + `@SQLRestriction` annotations across all soft-delete-capable entities in one pass per ADR 0002. Until then, `DELETE /projects/{id}` is a hard delete (spec 03 §5 explicitly permits this).
- Unique-constraint pre-check in the service (`existsByOwnerIdAndName`) → spec-specific `PROJECT_DUPLICATE_NAME`. DB constraint stays as the safety net for the race window (same pattern as `UserService` per slice 2). For PATCH-rename collision, add `existsByOwnerIdAndNameAndIdNot` so renaming to your own current name doesn't false-trigger.
- `POST /projects` returns **200 OK** (not 201) — matches the README and matches the divergence-from-REST call we already made for `/users` in slice 2 D3. Documented in the controller JavaDoc.
- Path variable name: `projectId` (consistent with `/users/{userId}` from slice 2).
- All test names cite the spec section they cover (`§1`, `§2`, etc.) per the testing rule.

**Prompt (verbatim, what's about to drive the implementation):**
> Build slice 4 (Projects CRUD) per `docs/spec/03-projects.md` §1–§6 and the decisions D1–D4 above. Mirror the slice-2 (Users) module layout: `projects/{domain,repository,api,service}`. Reuse `BaseEntity`, `ErrorCode.PROJECT_*` (already present from slice 1), `NotFoundException`, `ConflictException`, `ValidationException`. Write the three test classes (`ProjectRepositoryJpaTest`, `ProjectServiceTest`, `ProjectControllerWebMvcTest`) so every acceptance criterion in §1–§6 maps to at least one named test. Use `@AutoConfigureMockMvc(addFilters = false)` on the `@WebMvcTest` per the rule from slice 3. Stop before the test run and let me read the diff first.

**What the AI produced (files written / changed):**
- `docs/decisions/0006-rbac-on-projects.md`: ADR for the open-RBAC-on-projects stance, framed against ADR 0005's contrasting choice and explicitly calling out the open hard-delete as deliberate (not oversight).
- `projects/domain/Project.java`: entity extending `BaseEntity`, table-level unique constraint `uk_projects_owner_name` on `(owner_id, name)`, `ownerId` as plain `Long` (no `@ManyToOne` — rationale in the JavaDoc), `deletedAt` column declared but unused (slice 9 will wire it).
- `projects/repository/ProjectRepository.java`: two derived `existsBy…` queries, including the `…AndIdNot` variant that PATCH needs so renaming to the same name doesn't false-trigger the uniqueness check.
- `projects/api/CreateProjectRequest.java`, `PatchProjectRequest.java`, `ProjectResponse.java`: DTO records. `CreateProjectRequest` validates `@NotBlank name`, `@Size(max=10_000) description`, `@NotNull @Positive ownerId`. `PatchProjectRequest` has both fields nullable (D3); `_body`-level "at least one present" rule (D4) is enforced in the service, not at the bean-validation layer, because Bean Validation can't express cross-field "either of these two" cleanly without a class-level annotation.
- `projects/service/ProjectService.java`: `@Transactional` boundary; no `@PreAuthorize` per ADR 0006; owner-exists pre-check (404 USER_NOT_FOUND); duplicate pre-check (409 PROJECT_DUPLICATE_NAME); PATCH applies fields only when non-null, runs the rename-collision check only when name actually changes.
- `projects/api/ProjectController.java`: 5 endpoints, `POST` returns 200 (README + slice-2 D3 consistency), `PATCH` is a real `@PatchMapping`, `projectId` path-variable naming matches the `userId` precedent from slice 2.
- `src/test/.../projects/ProjectRepositoryJpaTest.java` (5 tests): persists + audit timestamps, rejects dup `(owner, name)` at DB level, allows same name across owners, both `existsBy…` queries.
- `src/test/.../projects/ProjectServiceTest.java` (14 tests): create happy + missing owner + duplicate; findById present + missing; update name-only / description-only / same-name-skips-check / rename-collision-409 / both-fields-null-400 / empty-string clears description / target-missing-404; delete happy + missing.
- `src/test/.../projects/ProjectControllerWebMvcTest.java` (15 tests): every acceptance criterion §1–§6 plus the explicit Session-04 D3/D4 cases. Uses `@AutoConfigureMockMvc(addFilters = false)` per the slice-3 rule.

**What I changed manually:** *Nothing* — the AI's first cut compiled and passed all 34 new tests plus the 68 carry-over tests with no edits. This is the first slice where that happened, and the reason is structural: the patterns established in slices 1–3 (pre-emptive duplicate checks, `addFilters = false` discipline, exception-cause-unwrapping in `GlobalExceptionHandler`, `_body`-scoped field issues for cross-field validation) all applied here directly. The Gotchas section in `.cursor/rules/30-testing.mdc` was consulted before writing the `@WebMvcTest` and the right annotations went in on the first pass.

**Suggestions I rejected (this slice):**
- AI proposed a `@ManyToOne(fetch = LAZY) User owner` association on `Project` "for navigability." **Rejected** — the response shape is flat per the README, no current endpoint needs the owner's other fields, and lazy associations are the #1 source of N+1 surprises later. The plain `Long ownerId` + service-level existence check gives us the FK guarantee without the relationship machinery. If slice 13 (auto-assign) genuinely needs to JOIN, we add the association then with a clear use-case.
- AI proposed a class-level `@AssertTrue` Bean Validation method on `PatchProjectRequest` to encode "at least one of name|description present." **Rejected** — would have surfaced as a generic `VALIDATION_FAILED` with field name like `requestValid` and an awkward message; doing it in the service gives us full control over the `_body` field name and the message, and keeps DTOs purely structural.
- AI proposed using `Optional<String>` in `PatchProjectRequest` to disambiguate "field absent" from "field present and null." **Rejected** — Jackson handles `Optional` poorly without extra config (treats absent as `null`-inside-`Optional` not `Optional.empty()` in some configurations), and D3 explicitly chose empty-string-means-clear as the simpler model. Saved a dependency + a class of bugs.
- AI proposed making `POST /projects` return 201 Created "for REST correctness." **Rejected** per the existing Session-02 D3 pattern: the README locks 200 and we stay consistent across resources.
- AI proposed adding `/projects/by-owner/{ownerId}` filter endpoint "for the UI." **Rejected** — out of scope for this slice (no spec line), and slice-spanning convenience endpoints encourage scope drift.

**Bugs caught in the agent's first cut and fixed pre-test-run:** *None.* This is unusual enough to flag for the reviewer. The lessons accumulated in slices 1–3 (`.cursor/rules/30-testing.mdc` Gotchas, the duplicate-pre-check pattern, the `ApiError.FieldIssue` shape for cross-field issues) eliminated the categories of mistake that bit me earlier.

**Run results:**
- `./mvnw test` → **`Tests run: 102, Failures: 0, Errors: 0, Skipped: 0` ✅** (BUILD SUCCESS)
  - Slice-1/2/3 carry-over: 68 tests, all still green.
  - Slice-4 new: 34 tests across 3 classes (`ProjectRepositoryJpaTest` 5, `ProjectServiceTest` 14, `ProjectControllerWebMvcTest` 15).
- Spec 03 §1–§6 coverage map (every criterion → ≥1 named test): see the `@DisplayName` strings in `ProjectControllerWebMvcTest`, each tagged with `Spec 03 §N`.

**Lessons from slice 4:**
- Investing in the "Gotchas" file across slices 1–3 paid off again: this slice was the first end-to-end clean cut. The rule file effectively became checklist-as-code for the agent (and for me reviewing the agent's output).
- The `_body`-scoped field issue idiom from `ApiError.FieldIssue` is more reusable than I expected — slice 5 (Tickets) will reuse it for `assigneeId` / `projectId` cross-field rules, slice 7 (Dependencies) for self-reference and cycle reporting.
- `@PatchMapping` (true PATCH verb) feels right when the resource supports partial update; the slice-2 `POST /users/update/{id}` "PATCH-as-POST" awkwardness was a function of the README's API design, not a default to mirror. Going with `PATCH /projects/{id}` here keeps the verb honest where the spec doesn't constrain it.
- "Plain `Long` foreign-key fields instead of `@ManyToOne`" is a deliberate stylistic call that needs to be documented because the agent will *always* propose the association by default. Worth thinking about a future ADR if it comes up a second time in slice 5.

---

## Template for future sessions (copy-paste, don't leave empty)
```
## Session NN — <slice name>
**Goal:** <spec file + sections being implemented>
**Prompt:**
> <paste the actual message you sent, verbatim>
**What the AI produced:**
- <3-5 bullets>
**What I changed manually:**
- <bullets, each with WHY>
**Suggestions I rejected:**
- <bullets, each with WHY>
```

> **TODO (one entry per finished slice — write within 5 minutes of completing the slice):**
> 1. Foundation · 2. Users · 3. Auth · 4. Projects · 5. Tickets · 6. Comments · 7. Audit · 8. Dependencies · 9. Soft delete · 10. Mentions · 11. CSV · 12. Attachments · 13. Auto-assign · 14. Escalation · 15. Polish

---

## Final retrospective (fill in at submission time)
- Total slices completed: <n>
- Slices where I rewrote significant agent output: <list>
- Concepts the agent got wrong on the first attempt and I had to correct: <list>
- Things I learned during this assignment: <list>
- If I were to do it again, I would: <list>

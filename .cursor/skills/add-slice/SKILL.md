---
name: add-slice
description: Add a new vertical feature slice to the IssueFlow Spring Boot backend using the spec-driven workflow this repo established across slices 1-14. Use when implementing a new feature end-to-end (entity + repository + service + REST controller + four-layer tests), when extending an existing slice with a new endpoint, or whenever the user mentions "slice", "spec-driven", "new feature", or asks to implement a requirement from `TDP_issueflow_requirements.pdf` / `README.md`.
---

# Add a Vertical Slice

A "slice" here is the unit of work this project ships in: one feature, taken from spec text to merged commit, with all four test layers passing. This skill encodes the recipe used 15 times (`docs/spec/01-users.md` through `docs/spec/13-escalation.md` plus polish).

## Workflow checklist

Copy this and tick as you go:

```
- [ ] Step 1: Surface decisions (Ask mode)
- [ ] Step 2: Write/update the spec doc
- [ ] Step 3: Add an ADR if a non-obvious decision was made
- [ ] Step 4: Implement entity -> repository -> service -> controller
- [ ] Step 5: Tests in the four-layer order (repo, service, web, integration)
- [ ] Step 6: Run the full suite (./mvnw test) - must stay green
- [ ] Step 7: Update prompts.md (decisions + bugs caught + test counts)
- [ ] Step 8: Update run.md test-count table
- [ ] Step 9: Commit (one slice = one commit; message format below)
```

## Step 1: Surface decisions

Before any code, **switch to Ask mode and enumerate the ambiguous calls** the spec doesn't pin down. Examples from past slices: "owner-only delete or any ADMIN?", "soft-delete cascade to children?", "AUTO_ASSIGN as USER or SYSTEM actor?". Wait for the user to approve each one with explicit text ("approve_all" or per-item).

**Anti-pattern:** writing code first and asking forgiveness later. Every slice that skipped this step (none did, intentionally) would have cost a rewrite.

Record each decision as a numbered bullet in `prompts.md` under a new `## Slice N — <feature>` section.

## Step 2: Write the spec doc

Create `docs/spec/NN-<feature>.md` mirroring the structure of `docs/spec/04-tickets.md` (a representative example):

```
# Spec NN: <feature>

## 1. Endpoints (table: METHOD | PATH | role | request body | 2xx | 4xx)
## 2. Data model
## 3. Validation rules
## 4. State / invariants (if any)
## 5. Error codes (table: ErrorCode | HTTP | when)
## 6. Audit events emitted
## 7. Open questions (resolved in prompts.md slice N decision log)
```

If the spec changes mid-implementation (it will), update this doc **first**, then change the code.

## Step 3: ADR (only when needed)

Add `docs/decisions/NNNN-<topic>.md` (copy `0000-template.md`) when a decision affects more than the current slice — e.g. "optimistic locking strategy" (0001), "soft-delete cascade behaviour" (0002), "project membership semantics" (0007). Skip if the decision is purely local.

## Step 4: Implementation order

Always entity → repository → service → controller. Package layout (mirror existing slices):

```
src/main/java/com/att/tdp/issueflow/<feature>/
  api/        <- @RestController, DTOs (records), request/response classes
  domain/     <- @Entity, value objects
  repository/ <- Spring Data interfaces
  service/    <- business logic, @Transactional boundaries
```

**Conventions enforced by `.cursor/rules/10-java-style.mdc`:**

- DTOs are `record`s. No Lombok on records.
- Entities extend `BaseEntity` for `id` / timestamps / `@Version`.
- Services use constructor injection via `@RequiredArgsConstructor` (Lombok).
- Controllers are thin — validation + DTO mapping + delegate. No business logic.
- RBAC via `@PreAuthorize("hasRole('ADMIN')")` at method level. Class-level only when EVERY endpoint shares the same role.

**Forward-compat hooks:** if a future slice will plug into your code, leave a `// TODO(slice-N): ...` comment AND mention the hook in your spec doc §7. Past examples: `SchedulingConfig` left a Clock bean for slice 14; `TicketService.update()` left an `isOverdue` reset hook for slice 14.

## Step 5: Tests — four layers, in this order

| Layer | Annotation | What it covers |
|-------|-----------|----------------|
| 1. Repository | `@DataJpaTest` | JPQL queries, custom finders, unique constraints, `@SQLDelete` SQL |
| 2. Service | plain JUnit + Mockito (`@Mock`, `@InjectMocks`) | every branch of every method |
| 3. Controller | `@WebMvcTest` + `@MockitoBean` + `addFilters = false` | happy path + each error code per endpoint |
| 4. Integration | `@SpringBootTest` + `MockMvc` (real beans, real H2) | end-to-end with login, audit assertions, cross-cutting RBAC |

Test count target per slice: **roughly 13-65 tests** depending on surface area. See `run.md` §6 for the per-slice breakdown.

**Read `.cursor/rules/30-testing.mdc` Gotchas before writing tests** — every entry there was earned the hard way. Especially:

- `@DataJpaTest` first-level cache hides optimistic-locking conflicts → hand-build a detached entity with a stale `@Version`.
- H2 in `MODE=PostgreSQL` rejects `BLOB` → use `columnDefinition = "BYTEA"` on `@Lob byte[]`.
- Integration tests must `auditLogs.deleteAll()` AFTER `/auth/login` (login writes a LOGIN audit row).
- Username regex `[A-Za-z0-9_]{3,32}` does NOT accept hyphens — use underscores in fixtures.

## Step 6: Run the full suite

```bash
./mvnw test
```

Must finish at `BUILD SUCCESS`. If you broke a prior slice, fix it before continuing — the suite is the contract. See the `run-tests-locally` skill for faster iteration patterns (`-Dtest=ClassName`, profile flags).

## Step 7: Update `prompts.md`

Append under your slice section:

```markdown
## Slice N — <feature>

### Decisions (approved <date>)
1. <decision> — rationale.
2. ...

### Implementation summary
<2-3 sentences on what shipped>

### Bugs caught (and fixed)
- <bug> — symptom; root cause; fix.

### Test results
- Slice tests: N
- Full suite: M / M passing
```

If a bug taught a generalisable lesson, **also append it to `.cursor/rules/30-testing.mdc` under Gotchas**. Future agents inherit the lesson.

## Step 8: Update `run.md` test-count table

Edit the table in `run.md` §6 — add a row for your slice and update the total.

## Step 9: Commit

One slice = one commit. Message format (verbatim from the project's history):

```
slice N: <feature> — <one-line outcome>

- <bullet of what was added>
- <bullet of what was added>
- tests: +N (running total: M)
```

Use a HEREDOC for the message to preserve formatting.

## Anti-patterns

- **Skipping Step 1** ("I'll just write it and ask after") — every contested decision becomes a rewrite.
- **Implementing without a spec doc** — the spec is what reviewers cross-check against the PDF.
- **Mixing two slices in one commit** — bisect becomes painful, and the spec/ADR/test correspondence breaks.
- **Adding a new `@Configuration` without grepping first** — past infra beans (Clock, scheduler) were pre-built in earlier slices with forward-compat JavaDoc. Always `Grep "@EnableScheduling"` / `Grep "Clock systemUTC"` first.

## Related skills

- `add-audit-event` — when your slice introduces a state-changing action that must hit the audit log.
- `add-fsm-transition` — when your slice extends or modifies a state machine.
- `run-tests-locally` — for the test-iteration loop in Step 5 / 6.

# AGENTS.md — IssueFlow (Spring Boot)

This file is read by any AI coding agent working in this repository (Cursor, Codex, Copilot, Claude Code, etc.).

## Project
- **Stack:** Java 21, Spring Boot 3.4, Spring Data JPA (Hibernate 6), Spring Security, PostgreSQL 16, JUnit 5, Maven (`mvnw`).
- **Spec sources of truth (read these BEFORE coding any feature):**
  1. `TDP_issueflow_requirements.pdf` (functional + extended requirements; lives alongside this repo or in the original assignment folder)
  2. `README.md` (the API contract — endpoint paths, request/response shapes)
  3. `docs/spec/*.md` (per-feature acceptance criteria, derived from 1+2)
- **Plan:** `docs/plan.md` lists the build order. Do not jump ahead.

## Working style
1. **Spec-driven.** Before writing code, open the spec file for the slice and restate its acceptance criteria in your plan. If the spec is ambiguous, stop and ask the user — do not guess.
2. **One slice at a time.** A slice = one feature package or one cross-cutting concern from `docs/plan.md`. Never edit code outside the current slice without explicit instruction.
3. **Tests are part of the slice.** A slice is not done until unit tests for the new service(s) and at least one `@WebMvcTest` (or `@SpringBootTest`) for the new endpoint(s) pass.
4. **Never commit.** The user reviews diffs and commits manually.
5. **Never add dependencies silently.** If a new Maven artifact is needed, propose it in chat with a one-line justification; wait for approval before editing `pom.xml`.
6. **Run after editing.** After any non-trivial edit, run `./mvnw -q test` and report results.

## Skills (`.cursor/skills/`)

Four project-scoped skills encode the playbooks we used repeatedly. Future agents (Cursor, Codex, etc.) auto-discover them via the YAML frontmatter:

| Skill | When to invoke |
|-------|----------------|
| `add-slice` | Implementing a new vertical feature end-to-end (entity → controller → tests) following the spec-driven workflow used 15 times. |
| `add-audit-event` | Wiring any new state-changing action into the audit log (spec 06) — picking the right actor (USER vs SYSTEM) and the right `AuditLogService` overload. |
| `add-fsm-transition` | Extending `TicketStatus` / `Priority` (or any new state machine), or modifying a transition guard. |
| `run-tests-locally` | Iterating on the suite — full run, by class, by layer, plus a fault-signature lookup table tied to `.cursor/rules/30-testing.mdc` Gotchas. |

Each `SKILL.md` is self-contained, under 200 lines, and references back to specs / rules / past slices for deeper context (progressive disclosure).

## Conventions (also enforced by `.cursor/rules/`)
- Feature-package layout under `com.att.tdp.issueflow.<feature>` (`user`, `auth`, `project`, `ticket`, `comment`, `audit`, `dependency`, `attachment`, `mention`, `csv`, `schedule`, plus `common`, `config`, `security`).
- DTOs use `jakarta.validation` annotations; controllers rely on `@Valid` + global `@RestControllerAdvice`.
- All errors flow through `GlobalExceptionHandler` and return the envelope defined in `.cursor/rules/20-api-contract.mdc`.
- Optimistic locking via JPA `@Version` on `Ticket` and `Comment`; conflicts → HTTP 409.
- Soft delete via Hibernate `@SQLDelete` + `@SQLRestriction` on `Ticket` and `Project`.
- Constructor injection only (Lombok `@RequiredArgsConstructor`). No field injection.
- No business logic in controllers; transactions live on service methods (`@Transactional`).

## When in doubt
- Prefer the simpler implementation that still meets the spec.
- Document any non-obvious decision in `docs/decisions/NNNN-<slug>.md` (ADR format).
- After completing a slice, append a section to `prompts.md` using the template at the bottom of that file.

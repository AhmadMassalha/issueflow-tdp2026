# Submission Instructions

This file is the reviewer's entry point. For substantive documentation, see [`README.md`](README.md).

---

## Who & what

- **Candidate:** Ahmad Massalha
- **Assignment:** AT&T TDP — IssueFlow (Ticket Management Backend Platform)
- **AI model used:** Claude Opus 4.7 (via Cursor — Ask mode for design, Agent mode for implementation)
- **Repository state:** all 14 functional slices + a final polish slice are complete, committed to `main`, and verified against real PostgreSQL.

## 60-second review path

1. **Read [`README.md`](README.md)** — has the 3-command quickstart, the test command, a documentation map, and a PDF-compliance matrix mapping every requirement section to ✅.
2. **Run the app** (requires Java 21+ and either Docker or a local PostgreSQL):
   ```bash
   docker compose up -d
   ./mvnw spring-boot:run
   open http://localhost:8080/swagger-ui.html
   ```
   Default login: `admin` / `admin` (seeded on first boot).
3. **Run the tests:**
   ```bash
   ./mvnw test
   ```
   Expect `Tests run: 462, Failures: 0, Errors: 0, Skipped: 0` (~30 seconds, no Docker required).
4. **Run the end-to-end smoke test** (optional but recommended — exercises every endpoint against real PostgreSQL):
   ```bash
   ./scripts/smoke.sh   # 87 assertions across 12 endpoint groups; exit 0 = all green
   ```
   See [`run.md` § "Automated smoke test"](run.md) for the full recipe.

## Where to read what

| Document | Purpose |
|---|---|
| [`README.md`](README.md) | Project overview, quickstart, API tables, PDF-compliance matrix |
| [`run.md`](run.md) | Full how-to-run guide: prereqs, two DB routes (Docker or local Postgres), build, JWT override, tests, smoke test, troubleshooting |
| [`prompts.md`](prompts.md) | Complete AI collaboration log (15 sessions, every decision documented with rationale + rejected alternatives, slice-by-slice retrospective, post-implementation smoke-test findings, and a "How this collaboration actually worked" disclosure at the top) |
| [`AGENTS.md`](AGENTS.md) | Working agreement for any AI agent entering the repo (Cursor / Codex / Claude Code / Copilot) — includes the skills index |
| [`docs/spec/*.md`](docs/spec/) | 13 per-feature acceptance criteria (the contract each slice was built against) |
| [`docs/decisions/*.md`](docs/decisions/) | 7 ADRs for non-local design decisions (optimistic locking, soft-delete cascade, token deny-list, stack choice, RBAC × 2, project membership semantics) |
| [`docs/plan.md`](docs/plan.md) | 14-slice build order with Definition-of-Done per slice |
| [`.cursor/rules/*.mdc`](.cursor/rules/) | Always-on conventions every agent loads (project context, Java style, API contract, testing). The `30-testing.mdc` "Gotchas" section has 23 hard-won lessons |
| [`.cursor/skills/*`](.cursor/skills/) | 4 on-demand playbooks: `add-slice`, `add-audit-event`, `add-fsm-transition`, `run-tests-locally` |

## What's verified

- **462 unit + integration tests passing** on H2 in PostgreSQL-compatibility mode (`./mvnw test`)
- **87 end-to-end smoke checks passing** against a real PostgreSQL 16 database (`./scripts/smoke.sh`)
- **Every PDF requirement implemented** — see the compliance matrix in `README.md` § "Compliance with PDF requirements"
- **No secrets in repository** — JWT secret is a clearly-marked dev fallback overridable via the `JWT_SECRET` environment variable; admin seed user (`admin/admin`) is documented and disableable via `app.seed.admin.enabled=false`

## Two interpretations I want to flag honestly

1. **Cycle detection on ticket dependencies (PDF §3.2)** — the PDF says "block until done" but does not literally say "no cycles." I added cycle detection because allowing cycles produces permanent deadlock (the DONE-blocker rule becomes unenforceable). Documented in [`docs/spec/07-dependencies.md`](docs/spec/07-dependencies.md) and the slice-8 retrospective in `prompts.md`. A strict literal reading could call this scope creep; I think it's defensible.
2. **Project membership for auto-assignment (PDF §3.8)** — the PDF says "the system queries all DEVELOPER users" without defining the scope. I implemented `members = {project.owner} ∪ {DEVELOPERs with ≥1 ticket in project}` to match the project-scoped intent (otherwise every developer in the system would be a candidate, including those with no relationship to the project). Documented in [`docs/decisions/0007-project-membership.md`](docs/decisions/0007-project-membership.md).

Both decisions were made in the open with the user, are documented in spec/ADRs, and can be reverted if the reviewer prefers a different interpretation.

## Submission contents

All deliverables live in this repository — there are no external files to attach. The reviewer needs only the repository URL (or a zip of this directory, excluding `target/`).

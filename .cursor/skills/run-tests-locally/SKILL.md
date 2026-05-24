---
name: run-tests-locally
description: Run the IssueFlow JUnit/Mockito/Spring test suite efficiently — full suite, single class, single method, by layer, or by feature package — and interpret common failure signatures (H2 contamination, optimistic-locking flake, JWT 401, login audit-row drift). Use when iterating on tests, when a CI run reports a regression, before committing a slice, or whenever the user mentions "run tests", "mvnw test", "test failure", or "BUILD FAILURE".
---

# Run the Test Suite

The suite is hermetic — H2 in PostgreSQL-compatibility mode, no Docker, no env vars. Target wall-clock: **~25-30 seconds** on a modern laptop for all 462 tests.

## Most-used commands

```bash
# Full suite (the only command that proves you didn't regress)
./mvnw test

# One test class (fastest iteration)
./mvnw test -Dtest=TicketServiceTest

# One method
./mvnw test -Dtest=TicketServiceTest#should_return409_whenStatusTransitionIsBackward

# All tests in one package (e.g. one slice)
./mvnw test -Dtest='com.att.tdp.issueflow.tickets.*'

# Skip tests entirely (for a quick compile/run check — NEVER for a commit)
./mvnw -DskipTests package

# Re-run only the failed tests from the previous run
./mvnw surefire:test -Dsurefire.rerunFailingTestsCount=1
```

`./mvnw` (the wrapper) downloads Maven 3.9.x on first use. No global Maven install needed.

## By layer

The project's four-layer test pyramid (see `.cursor/rules/30-testing.mdc`):

| Layer | Pattern | Run with |
|-------|---------|----------|
| Repository | `*RepositoryJpaTest` | `-Dtest='*RepositoryJpaTest'` |
| Service unit | `*ServiceTest` (no `WebMvc`/`Jpa` suffix) | `-Dtest='*ServiceTest'` |
| Controller slice | `*WebMvcTest` | `-Dtest='*WebMvcTest'` |
| Integration | `*IntegrationTest` | `-Dtest='*IntegrationTest'` |

Integration tests are the slowest (full `@SpringBootTest` boot per class). Run them last in your iteration loop.

## Reading the output

```
[INFO] Tests run: 462, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- **Failures** = an `assert*` failed (test ran, expectation didn't hold).
- **Errors** = the test threw an unexpected exception (often setup, not assertion).
- **Skipped** = `@Disabled` or assumption failure. We have **zero** skipped tests intentionally — investigate any non-zero count.

For per-class detail when a failure occurs:

```bash
cat target/surefire-reports/com.att.tdp.issueflow.tickets.TicketServiceTest.txt
```

## Common failure signatures and fixes

These are all documented in `.cursor/rules/30-testing.mdc` (project-wide rules, always loaded). Quick lookup table:

| Symptom | Root cause | Fix |
|---------|-----------|-----|
| `Table "ATTACHMENTS" not found` / similar | H2 in `MODE=PostgreSQL` rejects `BLOB` | Add `columnDefinition = "BYTEA"` to `@Lob byte[]` field |
| Every `@WebMvcTest` returns 401 | Spring Security default filter chain | Add `@AutoConfigureMockMvc(addFilters = false)` to the test class |
| Optimistic-locking test passes without exercising the conflict | `@DataJpaTest` first-level cache returns same managed instance | Hand-build a detached entity with stale `@Version`, then `saveAndFlush` |
| Audit-log count off by N | `/auth/login` writes a `LOGIN/USER` audit row | `auditLogs.deleteAll()` AFTER `login()`, before the action under test |
| Unique-constraint violation on `alice` / `bob` in `*RepositoryJpaTest` | `@SpringBootTest` committed rows leaked across classes | Namespace fixture usernames with a class prefix (e.g. `mrjt-alice`) |
| Integration test for async controller returns 500 | `OncePerRequestFilter.shouldNotFilterAsyncDispatch()` defaults to `true` | Override to `false` in `JwtAuthenticationFilter` |
| `expected:<200> but was:<401>` after adding `spring-security` | New filter chain catches your `@WebMvcTest` | `addFilters = false` (or write a dedicated `SecurityIntegrationTest`) |
| `MissingServletRequestPartException` → 500 instead of 400 | Default catch-all `Exception` handler | Add explicit `@ExceptionHandler(MissingServletRequestPartException.class)` to `GlobalExceptionHandler` |

For the full incident write-up of any signature above, see the matching Gotcha number in `.cursor/rules/30-testing.mdc` — every entry there cites the slice that earned it.

## Debugging a specific failure

```bash
# Enable verbose Spring boot logging just for one class
./mvnw test -Dtest=YourTest -Dlogging.level.org.springframework=DEBUG

# Enable SQL logging (great for repository tests)
./mvnw test -Dtest=YourRepositoryJpaTest \
    -Dspring.jpa.show-sql=true \
    -Dspring.jpa.properties.hibernate.format_sql=true

# Re-run the same test 5x to surface flakes
./mvnw test -Dtest=YourTest -Dsurefire.rerunFailingTestsCount=4
```

## Surefire reports

After every run, JUnit XML + plain-text reports land in `target/surefire-reports/`:

```
target/surefire-reports/
  TEST-com.att.tdp.issueflow.tickets.TicketServiceTest.xml   <- machine-readable
  com.att.tdp.issueflow.tickets.TicketServiceTest.txt        <- human-readable
  com.att.tdp.issueflow.tickets.TicketServiceTest-output.txt <- captured stdout/stderr
```

For a CI-style summary across all classes:

```bash
grep -E "Tests run.*Failures" target/surefire-reports/*.txt
```

## Pre-commit checklist

Before any `git commit` on a slice:

```
- [ ] ./mvnw test passes (full suite, not just your new class)
- [ ] No new Skipped tests
- [ ] No WARN logs in surefire-reports/<your test>-output.txt that aren't expected
- [ ] Your slice's test count matches the run.md table line you added/updated
```

If the full suite is broken by your slice, the fix is part of the same commit. Do not commit a known-broken state.

## When tests are slow

The full suite is ~25-30s. If yours suddenly takes 2+ minutes:

- `@SpringBootTest` without `@DirtiesContext` reuses the application context across classes — good. If you added a `@MockitoBean` somewhere, you may have inadvertently invalidated the context cache (Mockito beans force a new context per test class). Check the boot count in surefire output.
- Repository tests should be milliseconds each. If one takes seconds, you likely forgot `@DataJpaTest` and ended up with a full `@SpringBootTest`.

## Related skills

- `add-slice` — Step 6 of the slice recipe is "run the full suite".
- `add-audit-event` — the integration-test recipe there depends on running these tests.
- `add-fsm-transition` — Step 6 there breaks down the enum/service/integration test triad.

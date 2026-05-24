---
name: add-audit-event
description: Wire a new state-changing action into IssueFlow's audit log (spec 06) — pick the right actor (USER vs SYSTEM), the right `AuditLogService` overload, the right transactional boundary, and write the integration test that asserts the row. Use when adding any CREATE / UPDATE / DELETE / RESTORE / AUTO_* action, when introducing a new `AuditAction` enum value, when a code review flags a missing audit row, or whenever the user mentions "audit log", "AuditLog", "Actor.SYSTEM", or "performedBy".
---

# Add an Audit Event

The audit log is **append-only** and **transactionally coupled** to the change that produced it — spec 06 §1: *"if the txn rolls back, the audit row rolls back too."* That single sentence drives every choice below.

## Decision tree

1. **Is the action manually triggered by an authenticated user?**
   → use `auditLog.log(action, entityType, entityId, diff)` (resolves actor from `CurrentUserProvider`).
2. **Is the action triggered by a background job (`@Scheduled`, async worker, startup seeder)?**
   → use `auditLog.logSystem(action, entityType, entityId, diff)` (explicit SYSTEM, no request context).
3. **Is the action the LOGIN flow itself** (token issuance — `SecurityContext` not yet populated)?
   → use `auditLog.logAs(performedBy, action, entityType, entityId, diff)` (caller supplies the user id).
4. **Is the action triggered inside a request but on behalf of the system** (e.g. auto-assign during `POST /tickets`)?
   → use `auditLog.logSystem(...)` — the spec (§3.8) explicitly mandates `actor = SYSTEM` even though a user originated the request.

## Implementation pattern

```java
@Service
@RequiredArgsConstructor
@Transactional               // inherit caller's transaction (REQUIRED, the default)
public class YourService {

    private final YourRepository repo;
    private final AuditLogService auditLog;

    public Result performAction(Long entityId, ...) {
        // 1. Mutate state
        Entity e = repo.findById(entityId).orElseThrow(...);
        e.setX(...);
        repo.save(e);

        // 2. Write the audit row (same transaction)
        auditLog.log(
            AuditAction.UPDATE,
            EntityType.YOUR_ENTITY,
            entityId,
            diffJson(before, after)        // see "Diff format" below
        );

        return ...;
    }
}
```

**Never** wrap the audit write in `REQUIRES_NEW`. An audit row for a change that didn't actually commit is worse than no audit row.

## Adding a new `AuditAction`

If your event doesn't fit `CREATE / UPDATE / DELETE / RESTORE / LOGIN / LOGOUT / AUTO_ASSIGN / AUTO_ESCALATE`:

1. Add the enum value to `src/main/java/com/att/tdp/issueflow/common/enums/AuditAction.java`.
2. Update the JavaDoc to mark whether the action is USER- or SYSTEM-actor by default.
3. Update `docs/spec/06-audit.md` §1 (table of recorded actions).
4. Add a constraint test in `AuditLogRepositoryJpaTest` if your action has an unusual diff/actor combination.

## Diff format

Stored as `TEXT`. Convention from existing slices: a compact JSON object with `before` / `after` per changed field. Unchanged fields are omitted. Example:

```json
{"status":{"before":"TODO","after":"IN_PROGRESS"}}
```

For `CREATE`: only `after`. For `DELETE`: only `before` (or `null` to save tokens; both are conventional). For `AUTO_ESCALATE`: `{"priority":{"before":"MEDIUM","after":"HIGH"}}`. For `AUTO_ASSIGN`: `{"assigneeId":{"before":null,"after":42}}`.

Don't over-design — the diff is for human review, not programmatic consumption.

## Integration test (mandatory)

Every new audit-emitting code path needs an integration test that proves the row was written. Pattern (mirrors `AutoAssignIntegrationTest`):

```java
@SpringBootTest
@AutoConfigureMockMvc
class YourActionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuditLogRepository auditLogs;

    @BeforeEach
    void setUp() {
        wipe();           // helper: deleteAll on every repo
        seedUsers();
        seedFixtures();
    }

    @Test
    void shouldWriteAuditRow_whenActionPerformed() throws Exception {
        String token = login("admin", "admin");
        auditLogs.deleteAll();              // CRITICAL — login wrote a LOGIN row

        mvc.perform(post("/your-endpoint")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isCreated());

        var rows = auditLogs.findAll();
        assertThat(rows).hasSize(1);        // or N if your action also triggers AUTO_ASSIGN etc.
        var row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.YOUR_ACTION);
        assertThat(row.getActor()).isEqualTo(Actor.USER);  // or SYSTEM
        assertThat(row.getEntityType()).isEqualTo(EntityType.YOUR_ENTITY);
        assertThat(row.getDiff()).contains("\"before\":");
    }
}
```

### The `auditLogs.deleteAll()` after `login()` rule

**This is non-negotiable.** `POST /auth/login` writes a `LOGIN/USER` audit row before your test body runs. Every count assertion will be off by N (= number of `login()` calls) unless you wipe between login and the action under test. Documented in `.cursor/rules/30-testing.mdc` Gotcha #54 — caught by `AutoAssignIntegrationTest` on first run.

## Verifying nothing leaked

If your slice modifies an existing audit-emitting flow (e.g. extending `TicketService.update()`), check the existing integration tests for that flow — they may newly fail if your change adds a previously-absent row. Update their expected counts in the same commit.

## Anti-patterns

- **`@Transactional(propagation = REQUIRES_NEW)` on audit writes** — splits the audit row from the change. Spec violation.
- **Calling `auditLog.log(...)` from a background job** — `CurrentUserProvider.currentUser()` returns empty, so the row silently becomes SYSTEM/null. Works but is fragile; use `logSystem(...)` explicitly.
- **Writing the diff as Java's default `toString()`** — unreadable and not stable across refactors. Use a small `Map.of(field, Map.of("before", x, "after", y))` serialised via Jackson, or a `record` DTO.
- **Forgetting `auditLogs.deleteAll()` after login** in integration tests — guaranteed flaky count assertions.

## Related skills

- `add-slice` — for the surrounding feature workflow.
- `run-tests-locally` — for iterating on `*IntegrationTest` classes specifically.

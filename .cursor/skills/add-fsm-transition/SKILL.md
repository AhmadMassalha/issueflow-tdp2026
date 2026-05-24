---
name: add-fsm-transition
description: Safely evolve a finite state machine in IssueFlow — extend an enum (e.g. `TicketStatus`, `Priority`), add or remove a legal transition, or wire a guard against an illegal one. Use when modifying the ticket lifecycle FSM, when adding a new priority escalation rule, when relaxing or tightening the "DONE is immutable" invariant, when introducing a brand-new state machine in a future slice, or whenever the user mentions "FSM", "state transition", "canTransitionTo", "lifecycle", or "TICKET_INVALID_TRANSITION".
---

# Add or Modify an FSM Transition

This repo has two state machines:

| FSM | File | Transitions |
|-----|------|-------------|
| `TicketStatus` | `common/enums/TicketStatus.java` | Forward-only: `TODO → IN_PROGRESS → IN_REVIEW → DONE`. Backward and skip rejected. |
| `Priority` (escalation) | `common/enums/Priority.java` | One-way ratchet by escalator: `LOW → MEDIUM → HIGH → CRITICAL`. Manual change resets `isOverdue`. |

Both follow the same pattern: **the enum knows its own legal moves**, the **service enforces them**, the **DTO doesn't get to vote**.

## Workflow

```
- [ ] Step 1: Update the enum's transition method (canTransitionTo / escalate / next)
- [ ] Step 2: Update the enum-level JavaDoc with spec section reference
- [ ] Step 3: Add or update the ErrorCode for illegal transitions
- [ ] Step 4: Update the service guard (TicketService.update or similar)
- [ ] Step 5: Update the spec doc (e.g. docs/spec/04-tickets.md §7)
- [ ] Step 6: Tests — enum unit test + service branch test + integration test
- [ ] Step 7: Check forward-compat hooks (does the escalation scheduler still hold?)
```

## Step 1: Encode legality in the enum

The enum is the source of truth. Example shape (mirrors current `TicketStatus`):

```java
public enum YourState {
    A, B, C, D;

    public Optional<YourState> next() {
        return switch (this) {
            case A -> Optional.of(B);
            case B -> Optional.of(C);
            case C -> Optional.of(D);
            case D -> Optional.empty();   // terminal
        };
    }

    public boolean canTransitionTo(YourState target) {
        return next().map(n -> n == target).orElse(false);
    }
}
```

**Do not** put `boolean canTransitionTo(YourState target)` directly with a hand-written `if`-cascade. The `next() + canTransitionTo` split makes the enum self-documenting and the next-state queryable independently (used by the escalation scheduler).

For a non-linear FSM (e.g. branching graph), use `Set<YourState> next()` instead and adjust accordingly. Document why in the JavaDoc.

## Step 2: JavaDoc with spec pin

Every transition method must reference the spec section that authorised it:

```java
/**
 * Ticket lifecycle states.
 *
 * <p>Spec 04 §7 restricts movement to one-step forward only:
 * TODO → IN_PROGRESS → IN_REVIEW → DONE. Backward or skip transitions
 * are rejected by {@code TicketService#transition} with HTTP 409.
 */
```

Reviewers (human or AI) read JavaDoc first. Pinning the spec section makes drift detectable.

## Step 3: ErrorCode

Add or reuse an `ErrorCode` enum value. Existing relevant codes:

- `TICKET_INVALID_TRANSITION` — illegal `TicketStatus` move.
- `TICKET_DONE_IS_IMMUTABLE` — any update attempt after `DONE`.
- `TICKET_HAS_OPEN_BLOCKERS` — attempt to move to `DONE` with unresolved dependencies.

New error code? Add it to `common/web/ErrorCode.java` with HTTP status + human message, then document in your spec doc §5 (Error codes table).

## Step 4: Service guard

The service is where legality is enforced. Pattern from `TicketService.update`:

```java
if (existing.getStatus() == DONE) {
    throw new ApiException(TICKET_DONE_IS_IMMUTABLE);
}
if (request.status() != null && request.status() != existing.getStatus()) {
    if (!existing.getStatus().canTransitionTo(request.status())) {
        throw new ApiException(TICKET_INVALID_TRANSITION);
    }
    // additional invariants (e.g. open blockers for DONE)
    if (request.status() == DONE && dependencies.hasOpenBlockers(existing.getId())) {
        throw new ApiException(TICKET_HAS_OPEN_BLOCKERS);
    }
    existing.setStatus(request.status());
}
```

**Order matters.** Check `DONE` immutability first (cheapest, terminates fastest), then transition legality, then cross-cutting invariants. Mirror this ordering in tests.

## Step 5: Spec doc

Update `docs/spec/<NN>-<feature>.md` §4 (State / invariants). Include:

- A directed graph in plain text or mermaid.
- The full list of "from → to" pairs and what each requires.
- Cross-cutting invariants (e.g. "no transition to DONE while open blockers exist").

Example:

```
## 4. State / invariants

TODO ──► IN_PROGRESS ──► IN_REVIEW ──► DONE  (forward-only)

Invariants:
- A ticket in DONE rejects ALL updates (TICKET_DONE_IS_IMMUTABLE).
- Transition to DONE requires zero open blockers (TICKET_HAS_OPEN_BLOCKERS).
- Manual priority change resets isOverdue (spec 13 §3).
```

## Step 6: Tests

### a) Enum unit test

`src/test/java/com/att/tdp/issueflow/common/enums/YourStateTest.java` — exhaustive matrix:

```java
@ParameterizedTest
@EnumSource(YourState.class)
void canTransitionTo_onlyLegalForward(YourState from) {
    for (YourState to : YourState.values()) {
        boolean expected = (from.next().orElse(null) == to);
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
```

This is the cheapest, most exhaustive guard. Run it first.

### b) Service branch test (Mockito)

For each illegal transition, assert the specific `ErrorCode`:

```java
@Test
@DisplayName("rejects backward transition with TICKET_INVALID_TRANSITION")
void should_return409_whenStatusTransitionIsBackward() {
    var existing = ticketWithStatus(IN_REVIEW);
    when(repo.findById(1L)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> svc.update(1L, updateReq(TODO)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getCode())
        .isEqualTo(TICKET_INVALID_TRANSITION);

    verify(repo, never()).save(any());     // critical — assert no side effect
}
```

`verify(repo, never()).save(any())` is the assertion that catches accidental persists.

### c) Integration test

One per legal AND one per illegal transition, with status code + ErrorCode assertion. Mirror `TicketsIntegrationTest`.

## Step 7: Forward-compat check

Whenever you change an FSM, ask: **what else queries this enum?**

- `EscalationScheduler` calls `Priority.escalate()` — your change must not break the "CRITICAL never escalates further" invariant (spec 13 §3, idempotent).
- `TicketService.update()` resets `isOverdue` on manual priority change — preserve this hook.
- CSV import (`CsvImportRowExecutor`) parses statuses — your new state must round-trip through `TicketStatus.valueOf(...)`.
- `TicketResponse` exposes status — Swagger/OpenAPI will pick up new values automatically; no manual update needed.

Grep for all callers before merging:

```bash
rg "canTransitionTo|TicketStatus\.|Priority\." src/main
```

## Anti-patterns

- **Hand-coded `if`-cascade in the service** instead of letting the enum decide. Drift between service and enum guaranteed within 2 slices.
- **Validating status in the DTO** (`@Pattern(...)` style). The DTO must accept any syntactically valid enum value; legality is a runtime concern, not a parse concern. Otherwise you return 400 instead of 409 for an FSM violation, which mis-signals the cause.
- **Adding a new state without an exhaustive parameterised enum test.** A new state always reveals at least one switch-statement that's no longer exhaustive (Java will warn, but only when those files are recompiled).
- **Forgetting the "DONE is immutable" pre-check** when adding a new updatable field. The check must run BEFORE the new field's mutation.

## Related skills

- `add-slice` — for the surrounding workflow.
- `add-audit-event` — if your transition emits a new audit action (e.g. `AUTO_ESCALATE`).

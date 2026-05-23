# ADR 0004 — Stack choice: Spring Boot over NestJS

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** Ahmad Massalha

## Context
The assignment provides two skeletons: **Java 21 + Spring Boot 3.4** and **TypeScript 5 + NestJS 11**. The PDF (§1) explicitly allows either. Both stacks can satisfy every functional and extended requirement. The choice drives every subsequent technical decision (locking strategy, security stack, ORM, scheduler, validation framework) and shapes how the code is defended in the interview, so it warrants its own ADR.

## Options considered

### Option 1 — Java 21 + Spring Boot 3.4
- **Pros:**
  - The spec's hard requirements collapse to single annotations: `@Version` for optimistic locking, `@SQLDelete` + `@SQLRestriction` for soft delete, `@PreAuthorize("hasRole('ADMIN')")` for RBAC, `@Scheduled` for the escalation cron, `@Valid` + `jakarta.validation` for input validation.
  - The provided skeleton (`pom.xml`) already wires `spring-boot-starter-{web,data-jpa,validation}`, Lombok, PostgreSQL driver, H2 (test), and `commons-csv`. `application.yaml` already has the 10 MB multipart cap.
  - AT&T's TDP program runs in a Java-heavy environment. Spring code reads as "native" to the reviewer; explaining the stack costs zero interview time.
  - Strong typing + JPA constraints catch many classes of bug at compile time (enums, generics, null safety via `Optional`).
- **Cons:**
  - JVM warmup makes restart-driven iteration slower than Node's hot reload.
  - Higher boilerplate per CRUD slice (entity + repo + service + controller + DTOs).
  - Heavier runtime memory footprint (irrelevant for the reviewer but real).

### Option 2 — TypeScript 5 + NestJS 11
- **Pros:**
  - `npm run start:dev` gives instant hot reload — fastest iteration loop of the two.
  - `nest g resource <name>` scaffolds module + controller + service + DTOs + spec in one command — fewer keystrokes per slice.
  - `package.json` ships `@nestjs/typeorm`, `class-validator`, `multer`, `csv-parse`, `csv-stringify` — almost everything is already there.
  - Lower runtime memory footprint and faster cold start.
- **Cons:**
  - Optimistic locking via TypeORM `@VersionColumn` works, but the `OptimisticLockVersionMismatchError` must be caught and mapped to 409 by hand.
  - Soft delete via `@DeleteDateColumn` requires remembering `withDeleted: true` on every "deleted/restore" endpoint — an easy footgun if missed in one place.
  - RBAC requires writing a `RolesGuard` + `@Roles()` decorator manually (small but real).
  - The reviewer (likely Java-fluent in this org) would need a context switch before being able to read the code naturally.

## Decision
**Spring Boot.** Three reasons in order of weight:

1. **Defensibility (PDF §4.5).** The assignment explicitly says "you are fully accountable for that code, and be sure to understand it." That sentence determines which stack to pick — whichever I can explain in interview without flinching. My Spring familiarity is stronger than my Nest familiarity, so Spring wins on this dimension alone.
2. **Spec-to-annotation mapping.** Every requirement that has the highest risk of getting wrong (concurrent-edit prevention, soft delete that auto-filters every query, ADMIN-only routes, scheduled escalation, validation) collapses to one annotation in Spring. In Nest each one is a small custom implementation. Less surface area = fewer places to defend in interview.
3. **Reviewer fluency.** Clean Spring code is the dialect the AT&T TDP reviewer reads fluently. Going Nest would have meant burning the first interview minutes explaining the stack choice before being able to defend the code.

## Consequences
- **Iteration is slower** than Nest's hot-reload loop. Mitigated by writing tight `@WebMvcTest` / `@DataJpaTest` slice tests instead of restarting the full app to verify behavior.
- **More boilerplate per slice.** Mitigated by Lombok (`@RequiredArgsConstructor`, `@Slf4j`, `@Value`, `@Builder`) and by the "controllers stay thin" rule in `.cursor/rules/10-java-style.mdc`.
- **All concurrency, soft-delete, RBAC, and validation behavior gets the well-tested Hibernate/Spring implementation** — the only "novel" code is in service-layer business rules (FSM transitions, dependency cycle detection, mention extraction, escalation logic). That's where review attention should land, and now it can.
- **Transactional boundaries are clean.** Audit-log writes, auto-assignment, and dependency checks all live inside the same `@Transactional` as the state change they support, which is far cleaner than the equivalent Nest setup using `typeorm-transactional` or manual `dataSource.transaction()` blocks.
- **All other artifacts in this repo assume Spring** — AGENTS.md, the four `.cursor/rules/*.mdc` files, all 13 specs, and ADRs 0001–0003 are Spring-specific. Flipping the stack later would require rewriting all of them.

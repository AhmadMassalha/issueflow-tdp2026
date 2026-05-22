# Spec 13 — Auto escalation scheduler

## Trigger
`@Scheduled(fixedDelayString = "${escalation.fixed-delay-ms:300000}")` (default every 5 minutes; configurable in `application.yaml`). Enable globally via `@EnableScheduling` on a `@Configuration` class.

## Algorithm
1. Select tickets where `due_date IS NOT NULL AND due_date < now() AND status != 'DONE' AND deleted_at IS NULL`.
2. For each ticket with `priority < CRITICAL`: bump priority one level (LOW→MEDIUM→HIGH→CRITICAL). Save. Write an audit row: `actor=SYSTEM, action=AUTO_ESCALATE, diff={ "from": <old>, "to": <new> }`.
3. For tickets at CRITICAL still overdue: set `isOverdue = true` (idempotent — only update if currently false; produces no audit row when no change).

## Acceptance criteria
1. Manual PATCH of `priority` clears `isOverdue` (sets to false) and resets the escalation cycle. The next cron pass re-evaluates from the new priority. No special "skip" flag.
2. Escalation never modifies `status`.
3. Tickets without `dueDate` are never escalated.
4. Idempotency: a CRITICAL ticket is never re-escalated; its audit log contains at most one `AUTO_ESCALATE` row per actual priority change.
5. All `GET` ticket responses expose `isOverdue` as a boolean.
6. The job runs inside `@Transactional` per ticket batch (or per ticket if simpler) to keep failures contained.

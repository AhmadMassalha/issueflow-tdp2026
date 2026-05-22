# IssueFlow build plan (Spring Boot)

The repo is built in **14 slices**. Each slice = one commit + one section in `prompts.md`. Tests required per slice.

| # | Slice | Spec | Key Spring pieces |
|---|---|---|---|
| 1 | Foundation | — | `BaseEntity` (with `@CreatedDate`/`@LastModifiedDate`), enums, `ApiError`, `ErrorCode`, `GlobalExceptionHandler`, domain exceptions, JPA auditing, `application-test.yaml` for H2 |
| 2 | Users CRUD | `docs/spec/01-users.md` | `User` entity, `UserRepository`, `UserService`, `UserController`, DTOs, Bean Validation |
| 3 | Auth (JWT) | `docs/spec/02-auth.md` | `BCryptPasswordEncoder`, `JwtService` (jjwt), `JwtAuthenticationFilter`, `SecurityFilterChain`, `TokenDenyListService`, `@PreAuthorize`, method security, `RoleHierarchy` if needed |
| 4 | Projects CRUD | `docs/spec/03-projects.md` | `Project` entity, FK to `User` (owner) |
| 5 | Tickets CRUD + FSM + locking | `docs/spec/04-tickets.md` | enums, `@Version`, FSM in service, `dueDate`, `isOverdue` |
| 6 | Comments CRUD + locking | `docs/spec/05-comments.md` | `@Version` |
| 7 | Audit log | `docs/spec/06-audit.md` | `AuditLog` entity, `AuditLogService.log(...)` called from every state-changing service method (same `@Transactional`), `GET /audit-logs` with JPA `Specification` filters |
| 8 | Ticket dependencies | `docs/spec/07-dependencies.md` | join entity, same-project validation, DONE-blocker gating, cycle detection |
| 9 | Soft delete + restore | `docs/spec/08-soft-delete.md` | `@SQLDelete` + `@SQLRestriction`, dedicated repo method for `withDeleted`, ADMIN-only restore via `@PreAuthorize` |
| 10 | Mentions | `docs/spec/09-mentions.md` | regex extractor, `Mention` entity, `/users/{id}/mentions` with paginated response envelope |
| 11 | CSV export/import | `docs/spec/10-csv.md` | `commons-csv` `CSVPrinter`/`CSVParser`, multipart, streaming response |
| 12 | Attachments | `docs/spec/11-attachments.md` | `MultipartFile`, MIME + size validation, `BYTEA` via `@Lob` |
| 13 | Auto-assign by workload | `docs/spec/12-auto-assign.md` | workload query, tie-break by user id; `/projects/{id}/workload` |
| 14 | Escalation scheduler | `docs/spec/13-escalation.md` | `@EnableScheduling`, `@Scheduled(fixedDelay = ...)`, `is_overdue` flag, reset on manual priority change |
| 15 (cleanup) | Polish | — | `run.md` finalize, README sanity, full `./mvnw test` pass, `prompts.md` retrospective section, optional springdoc/OpenAPI |

## Dependencies added per slice (record here as they are introduced)
- Slice 1: — (validation, JPA, web are already in `pom.xml`)
- Slice 3: `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-api:0.12.x`, `jjwt-impl`, `jjwt-jackson`
- Slice 14: — (`@Scheduled` ships with Spring; just enable it)
- (Optional, Slice 15): `org.springdoc:springdoc-openapi-starter-webmvc-ui`

## Definition of Done for every slice
1. Spec file exists and lists acceptance criteria.
2. Implementation follows `.cursor/rules/`.
3. `./mvnw -q test` passes.
4. At least one happy-path and one error-path test per new endpoint.
5. `prompts.md` updated with the slice section.
6. Manual diff review by the developer before commit.

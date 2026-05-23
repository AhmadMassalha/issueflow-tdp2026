# Running IssueFlow

This guide takes a reviewer from a clean machine to a working API in under five
minutes. It is intentionally exhaustive — every implied step (DB role creation,
JWT secret, default admin, where Swagger lives) is spelled out.

If anything here disagrees with the code, the code wins; please open an issue.

---

## TL;DR (3 commands once prerequisites are installed)

```bash
docker compose up -d            # PostgreSQL 16 on localhost:5432
./mvnw spring-boot:run          # app on http://localhost:8080
open http://localhost:8080/swagger-ui.html
```

Default login: `admin` / `admin` (a WARN is logged at startup reminding you to
disable the seeder for production — see `app.seed.admin.enabled`).

---

## 1. Prerequisites

| Tool         | Version     | Verify                              |
| ------------ | ----------- | ----------------------------------- |
| **Java**     | 21 (LTS)    | `java -version` → `21.x.x`          |
| **Maven**    | bundled     | use `./mvnw` — no global install    |
| **Docker**   | Engine 20+  | `docker --version`                  |
| **psql**     | optional    | for the `\dt` sanity check          |

`./mvnw` automatically downloads Maven 3.9.x on first invocation, so you only
need the JDK and Docker.

**macOS quick install:**

```bash
brew install --cask temurin@21   # Eclipse Temurin JDK 21
brew install --cask docker       # Docker Desktop
```

After installing Docker.app, launch it once so the daemon is running.

---

## 2. Start PostgreSQL

The repo ships a `compose.yml` that creates a Postgres 16 container with the
exact role and database the app expects (`issueflow` / `issueflow` on db
`issueflow`, port 5432):

```bash
docker compose up -d
docker compose ps               # confirm "healthy"
```

### Already have Postgres on :5432?

If you've got a local Postgres install (Homebrew, Postgres.app, etc.) and
don't want to use Docker, create the role + database it expects:

```bash
psql -h localhost -U "$(whoami)" -d postgres <<'SQL'
CREATE ROLE issueflow WITH LOGIN PASSWORD 'issueflow';
CREATE DATABASE issueflow OWNER issueflow;
SQL
```

To point the app at different credentials/host, override in
`src/main/resources/application.yaml` or via env vars (`SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`).

---

## 3. Build & run

```bash
./mvnw spring-boot:run
```

Expected boot output (trimmed):

```
================================================================
 jwt.secret is the dev fallback baked into application.yaml.
 This is fine for local development and graded review runs.
 For any non-local deployment, override via the JWT_SECRET env
 variable with a random 32+ byte secret (e.g.
   export JWT_SECRET=$(openssl rand -base64 48) ).
================================================================
... AdminSeeder created default admin user (username=admin) ...
... Tomcat started on port 8080 (http) with context path '/'   ...
... Started IssueFlowApplication in N.NN seconds                ...
```

Two intentional WARNs you will see:

1. **jwt.secret dev fallback** — silenced by `export JWT_SECRET=...`.
2. **Default admin/admin user created** — silenced by setting
   `app.seed.admin.enabled: false` (or `APP_SEED_ADMIN_ENABLED=false`)
   after you've made your own ADMIN account.

To package an executable jar instead:

```bash
./mvnw clean package
java -jar target/issueflow-0.0.1-SNAPSHOT.jar
```

---

## 4. Explore the API in Swagger UI

Open <http://localhost:8080/swagger-ui.html>. It redirects to the canonical
`/swagger-ui/index.html`.

To call a protected endpoint:

1. Expand `POST /auth/login`, click **Try it out**, send
   `{"username":"admin","password":"admin"}`. Copy the `accessToken` from the
   response.
2. Click the green **Authorize** button (top-right), paste the token (no
   `Bearer ` prefix), click **Authorize**, **Close**.
3. Every subsequent request now ships `Authorization: Bearer …`
   automatically.

OpenAPI spec (JSON) is at <http://localhost:8080/v3/api-docs> if you'd rather
import it into Postman / Insomnia / Bruno.

---

## 5. Manual smoke test with curl

```bash
# 1) Login as the seeded admin
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")

# 2) Whoami
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/auth/me

# 3) Create a project
PROJECT_ID=$(curl -s -X POST http://localhost:8080/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Demo\",\"description\":\"reviewer smoke test\",\"ownerId\":1}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

# 4) Create a ticket on that project
curl -s -X POST http://localhost:8080/tickets \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"projectId\":$PROJECT_ID,\"title\":\"First bug\",\"description\":\"oops\",\"status\":\"TODO\",\"priority\":\"MEDIUM\",\"type\":\"BUG\"}"

# 5) List tickets on the project
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/tickets?projectId=$PROJECT_ID"
```

---

## 6. Run the test suite

```bash
./mvnw test
```

The suite is hermetic — it runs against H2 in PostgreSQL-compatibility mode
and needs **no Docker, no Postgres, no env vars**. Expect:

```
[INFO] Tests run: 145, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Breakdown (so you know what's being exercised):

| Layer                 | Tests | Slice |
| --------------------- | ----: | ----- |
| Cross-cutting (base entity, global errors)        | 13 | 1 |
| Users (repo + service + web)                      | 29 | 2 |
| Auth (JWT, deny list, integration)                | 30 | 3 |
| Projects (repo + service + web)                   | 30 | 4 |
| Tickets (repo + service + web — FSM, optimistic locking, soft-delete column) | 43 | 5 |
| **Total**                                         | **145** | |

---

## 7. Configuration reference

Every property below can be overridden via environment variables using
Spring Boot's relaxed binding (`jwt.secret` → `JWT_SECRET`,
`app.seed.admin.enabled` → `APP_SEED_ADMIN_ENABLED`, etc.).

| Property                       | Default                       | Notes |
| ------------------------------ | ----------------------------- | ----- |
| `server.port`                  | `8080`                        |       |
| `spring.datasource.url`        | `jdbc:postgresql://localhost:5432/issueflow` | matches `compose.yml` |
| `spring.datasource.username`   | `issueflow`                   |       |
| `spring.datasource.password`   | `issueflow`                   |       |
| `spring.jpa.hibernate.ddl-auto`| `update`                      | flips to `create-drop` in tests |
| `jwt.secret`                   | dev sentinel (see WARN)       | **must be 32+ bytes for HS256**; set `JWT_SECRET` for any non-local run |
| `jwt.expires-in-seconds`       | `3600`                        | per spec 02 §1 |
| `jwt.issuer`                   | `issueflow`                   |       |
| `app.seed.admin.enabled`       | `true`                        | seeds `admin/admin` on first boot if no ADMIN exists |
| `app.seed.admin.username`      | `admin`                       |       |
| `app.seed.admin.password`      | `admin`                       |       |

---

## 8. Troubleshooting

| Symptom                                                              | Fix |
| -------------------------------------------------------------------- | --- |
| `IllegalStateException: jwt.secret must be at least 32 bytes`        | Your override is too short. Use `openssl rand -base64 48`. |
| `role "issueflow" does not exist`                                    | See §2 — either `docker compose up -d` or run the `CREATE ROLE` snippet. |
| `Port 8080 already in use`                                           | `lsof -nP -iTCP:8080 -sTCP:LISTEN` then `kill <pid>`, or override `SERVER_PORT=8081`. |
| `401 Unauthorized` from every endpoint except `/auth/login`          | You forgot the `Authorization: Bearer <token>` header (or the token has expired — they last 1h). |
| Swagger UI fails to load static assets                               | We pinned springdoc to **2.7.0** because 2.8.x crashes Spring Framework 6.2's PathPattern parser. Don't bump without re-testing. |

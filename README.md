# TaskFlow

A Java 21 / Spring Boot task API backed by PostgreSQL, with JWT authentication, Flyway migrations, and paginated task listing.

## Local setup

Install JDK 21 and Docker with Docker Compose. Start Docker and wait until its daemon responds. The Maven wrapper downloads Maven and dependencies when needed. Tests use isolated PostgreSQL Testcontainers and require Docker; they do not use your personal database or require your local `.env`.

From the repository root, create a local environment file only if absent:

```sh
if [ ! -e .env ]; then cp .env.example .env; fi
```

Preserve any existing `.env`. Open it locally and fill in missing values from `.env.example`. For a new environment, generate the two secrets with:

```sh
openssl rand -hex 24
openssl rand -base64 32
```

Use the first output as `DB_PASSWORD` and the second as `JWT_SECRET`.
`JWT_SECRET` is a base64-encoded random signing key. Keep secrets in `.env` or your deployment secret storage; commit only the empty example placeholders.
`DB_USERNAME` defaults to `taskflow`; `JWT_EXPIRATION` defaults to 900 seconds.
JWT settings also include `JWT_ISSUER=taskflow`, `JWT_AUDIENCE=taskflow-api`, and `JWT_CLOCK_SKEW=30`.
For an existing database volume, retain its username and password. Changing Compose environment values does not change credentials already stored in PostgreSQL.

Run the tests, package the application, and start the stack:

```sh
./mvnw clean test
./mvnw clean package
docker compose up --build
```

Packaging also runs the tests. The current Dockerfile requires the packaged JAR in `target/` before the image is built. Compose starts the API with the `prod` profile and waits for the PostgreSQL health check.

The API is at `http://localhost:8080`. PostgreSQL is published on localhost port 5433; inside Compose the API connects to `postgres:5432`. Flyway manages schema changes, and Hibernate validates the schema. Database data lives in a named volume. `docker compose down` stops the stack while preserving that volume.

Compose automatically reads `.env` for variable interpolation. Spring Boot does not automatically load `.env` when run directly. For a direct Java/Maven launch, export `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` in your shell or IDE. The example `DB_URL` points at Compose's published port 5433. Start the database with `docker compose up -d postgres`, then run:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The development profile exposes Swagger at `http://localhost:8080/swagger-ui.html`; the production profile disables it.

## Authentication and a task

Register a new example user, then log in:

```sh
curl -i -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"learning-user","password":"learning-password-123"}'

curl -sS -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"learning-user","password":"learning-password-123"}'
```

Copy the response's `token` value into a local shell variable:

```sh
export TASKFLOW_TOKEN='paste-the-returned-token-here'

curl -i -X POST http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer $TASKFLOW_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Learn Spring pagination","description":"Stage 13","priority":5}'
```

Registration returns 201; duplicate usernames return 409. Incorrect login
credentials and missing/invalid bearer tokens return 401. Invalid request data
returns 400. Task priority is optional; supplied values must be 1 through 5.

## Stage 16 JWT security

Bearer tokens use stable user-ID subjects, version claims, issuer/audience checks, required timestamps/UUID IDs, and HS256. Existing username-subject tokens require login again after this upgrade. Lifetime is 60–3600 seconds (default 900), skew 0–60 (default 30), and the validated base64 signing key must contain at least 32 bytes.

```sh
curl -i -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Authorization: Bearer $TASKFLOW_TOKEN"
```

Logout returns 204 and revokes all of that user's existing tokens through database token-version checks. Discard the client token and log in again; no refresh endpoint exists. Disabled/deleted users and revoked tokens return 401. V6 preserves users/tasks while adding enabled/token-version/optimistic-lock state; checks run during bearer decoding, so acceptance depends on current database state.

Login and registration share 30 requests per remote IP per 60 seconds, returning 429 with `Retry-After`. This bounded in-memory limiter ignores forwarded-IP headers, resets on restart, and requires a gateway/shared replacement before multi-instance scaling.

Tokens use Authorization headers, without auth cookies. Swagger is same-origin; a future browser frontend needs an explicit CORS allowlist. One environment signing key is supported: replacing it and restarting every instance consistently revokes all old tokens. Overlapping-key rotation is not implemented. See the Stage 16 learning file for details.

## Stage 13 list contract

`GET /api/v1/tasks` now returns a page envelope. Clients previously reading an
array must read `response.items` and use the accompanying page metadata:

```json
{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

Defaults are page 0, size 20, and sort `id,desc`. Size must be 1–100. A valid
page beyond the last page has empty items and retains accurate totals. Negative
pages, unsupported values, and offsets beyond JPA's integer range return 400.

```sh
curl -sS --get http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer $TASKFLOW_TOKEN" \
  --data-urlencode 'page=0' --data-urlencode 'size=10' \
  --data-urlencode 'status=TODO' --data-urlencode 'priority=5' \
  --data-urlencode 'title= spring ' \
  --data-urlencode 'sort=priority,desc' --data-urlencode 'sort=id,asc'
```

Optional status, priority, and title filters combine with AND. Status is `TODO`,
`IN_PROGRESS`, or `COMPLETED`; priority matches exactly. Title is a trimmed,
case-insensitive substring with literal `%`, `_`, and `!` handling. A blank title
adds no filter; a nonblank title may contain at most 100 characters.

Sort fields are `id`, `title`, `status`, and `priority`. Repeat `sort` for multiple
fields; a field alone defaults to ascending. Duplicate fields and invalid
directions return 400. An explicit sort without ID gets an `id,asc` tie-breaker.
Concurrent edits can still shift page boundaries.

The legacy `/api/v1/tasks/search?title=...` and `/api/v1/tasks/status/TODO` routes
retain DTO arrays and are deprecated. Use the main list route for new clients.

## Stage 14 task-to-user relationship

Tasks reference users through lazy `Task.owner`. V4 adds a nullable foreign key and index. Deleting a task preserves its user; deleting a referenced user is rejected. DTOs exclude user entities/passwords.

Legacy tasks remain unassigned until verified operator backfilling. Stage 15 assigns new tasks to their authenticated creator.

## Stage 15 authorization and ownership

Task routes require USER or ADMIN and a persisted user matching the JWT user ID.
Every read, count, and mutation is owner-scoped; ADMIN also manages only its own tasks. Both HTTP and service layers check identity/roles.

Server-assigned ownership cannot be changed by request input; DTOs and the page envelope stay unchanged.
Missing/inaccessible/unassigned task IDs return 404, anonymous/unknown/deleted users return 401, and unsupported roles return 403. The Stage 15 learning file includes a two-user demo.

V5's `CHECK (owner_id IS NOT NULL) NOT VALID` preserves legacy rows but rejects new/updated unowned rows. Legacy tasks remain hidden from every role. No automatic assignment or public claim API exists; verified backfilling must precede a global `NOT NULL` constraint.

## Stage 17 dynamic querying

Task list, status, and title queries now share composable JPA specifications with mandatory owner scope and optional AND-combined filters. Pagination, counts, sorting, DTOs, and the public API remain unchanged; no schema migration is required.

Legacy search preserves raw-title behavior: spaces matter, empty titles match all owned tasks, and overlong terms are accepted without the main list's length validation (normally no matches). It retains database `UPPER(title) LIKE UPPER(pattern)`; the main route retains `LOWER(title)` with a Locale.ROOT-lowered term. Unicode cases such as Greek σ and ς can differ, so both are intentional. Date filters wait for stage 18 auditing.

## Learning notes and roadmap

- [Baseline understanding](docs/understanding/00_baseline_understanding.txt)
- [Stage 13 understanding](docs/understanding/13_pagination_filtering_understanding.txt)
- [Stage 14 understanding](docs/understanding/14_jpa_relationships_understanding.txt)
- [Stage 15 understanding](docs/understanding/15_authorization_ownership_understanding.txt)
- [Stage 16 understanding](docs/understanding/16_jwt_security_understanding.txt)
- [Stage 17 understanding](docs/understanding/17_dynamic_querying_understanding.txt)
- [Development plan](DEVELOPMENT_PLAN.md)

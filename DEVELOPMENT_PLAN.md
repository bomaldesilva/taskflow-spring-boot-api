# TaskFlow development plan — stages 13–30

Prepared 23 October 2026 from the current repository. Baseline stabilization and stages 13–14 are implemented and verified; stages 15–30 remain planned.

Latest verification: `./mvnw clean test` — 99 tests, zero failures, zero errors, zero skipped. This includes 41 pagination/filtering cases and eight new relationship/migration cases against PostgreSQL Testcontainers. Learning notes are in `docs/understanding/00_baseline_understanding.txt`, `docs/understanding/13_pagination_filtering_understanding.txt`, and `docs/understanding/14_jpa_relationships_understanding.txt`.

## Direction

Your stages form a good learning roadmap from a CRUD application to an operated backend. Keep TaskFlow as one Spring Boot application while developing it. Add infrastructure when it supports a concrete use case or an explicit learning goal.

The stage numbers below match your list. For delivery, bring ownership, essential error handling, tests, CI, and documentation forward. Redis and Kubernetes are optional extensions rather than requirements for the first useful release.

## Starting point before baseline stabilization and stage 13

The repository already contains:

- Java 21 configuration, Spring Boot, Maven wrapper, and layered controllers/services/repositories.
- Task CRUD, validation, status and title searches, DTOs, and a basic `ProblemDetail` exception handler.
- PostgreSQL, Flyway migrations V1–V3, and H2/PostgreSQL Testcontainers tests.
- User registration/login, password encoding, JWT issuance/validation, and role claims.
- A Dockerfile, local Compose stack, Actuator, one custom task counter, and OpenAPI annotations.

At that starting point, tasks had no owner, queries operated on all tasks, and lists were unbounded. The previous suite contained 30 passing tests, but mock authentication did not establish that real token issuance and validation worked together. The baseline and stage 13 added real authentication coverage and pagination; stage 14 adds the optional user relationship. Ownership assignment and access restrictions remain for stage 15.

## Before starting the next feature

Treat these as a small baseline stabilization milestone:

- [x] Fix `TaskService.updateTask()` so it persists the supplied priority; add a regression assertion on stored data.
- [x] Return `TaskResponse` from status and search endpoints. Do this before adding relationships.
- [x] Derive auth response `expiresIn` from the same configuration as token expiry.
- [x] Add a real register → login → bearer token → protected endpoint test.
- [x] Map bad login credentials to 401, duplicate usernames to 409, and malformed input to 400. Complete the broader error design in stage 20.
- [x] Exclude `.env`, runtime logs, and build output from future commits/build contexts; provide placeholder environment examples. Preserve existing local data while cleaning repository tracking.
- [x] Confirm `./mvnw clean test` with Docker running, and record the baseline.

Production JWT settings and Compose forwarding are now configured. Production uses Flyway-managed migrations plus Hibernate schema validation, with routine SQL logging disabled. A production-profile integration test verifies startup and token handling; the container build/deployment work remains in stage 23.

## Recommended execution order

| Milestone | Work | Exit condition |
|---|---|---|
| A — Reliable baseline | Stabilization above; begin essential parts of 20, 21, 22, and 25 | Real authentication works; a clean checkout can run required tests in CI |
| B — Private task API | 14 → 15 → 13 → 16 | Users can manage only their own tasks through a bounded, documented API |
| C — Maintainable backend | 17 → 18 → 19; finish 20 and 21 | Queries, auditing, errors, migrations, and performance have evidence-backed checks |
| D — Deployable release | Finish 22 → 23 → 24 → 25 → 28 | Tested container runs over HTTPS with backups, visibility, and a rollback procedure |
| E — Advanced learning | 26 and 27 when justified; 29 if wanted; 30 alongside cloud infrastructure | Each addition demonstrates a measurable benefit or a stated learning outcome |

For a strictly sequential course, following 13–30 is also workable. Keep the same release gates: establish ownership before shared use and production configuration before deployment. Testing and documentation accompany every stage.

## Stage 13 — Pagination / filtering

**Outcome:** task listing remains predictable as data grows.

- [x] Support a single list endpoint such as `GET /api/v1/tasks?page=0&size=20&status=TODO&priority=5&title=spring&sort=id,desc`.
- [x] Use repository-level pagination and a stable DTO envelope containing `items`, `page`, `size`, `totalElements`, and `totalPages`.
- [x] Define page defaults and limits: page 0, size 20, maximum size 100. Reject invalid values and unsupported JPA offsets with 400.
- [x] Allowlist sortable fields; include an ID tie-breaker where sort values can repeat. Null priority remains supported.
- [x] Document the main list's array-to-envelope contract change; update examples and tests. Legacy search/status routes retain deprecated array responses.

**Done when:** first, final, empty, and out-of-range pages behave as documented; combined filters and invalid values are tested. Every list is owner-scoped after stage 15. Stable sorting does not guarantee a frozen dataset across concurrent edits; defer cursor pagination until needed.

**Touchpoints:** `TaskController`, `TaskService`, `TaskRepository`, response DTOs. Stage 17 later refactors filter composition without changing this API.

## Stage 14 — JPA relationships

**Outcome:** each task can belong to an existing user.

- [x] Add a lazy, unidirectional `Task.owner` many-to-one relationship to `AppUser`, backed by an `owner_id` foreign key.
- [x] Append Flyway V4 with a nullable owner column and index. Preserve existing tasks as unassigned; leave `NOT NULL` and legacy access policy for stage 15 without assigning old tasks arbitrarily.
- [x] Keep the five-field task DTO contract unchanged. Verify assigned-owner responses with session-in-view disabled and mapping without initializing detached owners.
- [x] Define deletion behavior: no JPA cascades, and `ON DELETE RESTRICT` prevents deleting a referenced user. Deleting a task preserves its user.

**Verified:** V3 → V4 preserves existing task/user data; valid user links persist; invalid references and referenced-user deletion are rejected. API-created tasks remain unassigned until stage 15 sets their authenticated creator.

**Done when:** fresh and populated databases migrate successfully; owner references are valid; serialization does not expose user entities or trigger recursive relationships.

**Dependency:** this enables stage 15. Complete the two stages together before allowing multiple users to share the application.

## Stage 15 — Authorization / ownership

**Outcome:** authentication becomes enforceable data isolation.

- [ ] Resolve the authenticated principal to a persisted user. Set task ownership on the server; creation/update requests cannot choose another owner.
- [ ] Scope list, ID lookup, status lookup, search, update, status change, and delete operations by owner in service/repository queries.
- [ ] Make the role policy explicit: start with users managing their own tasks. Define any ADMIN access separately and test it.
- [ ] Adopt a consistent policy for inaccessible task IDs, proposed as 404 for owner-scoped resources. Use 403 for explicit role-restricted operations.

**Done when:** two persisted users cannot read or change each other's tasks through any route, filter, or mutation. Anonymous requests return 401. Tests also call services where relevant so controller-only checks cannot hide an authorization gap.

**Dependencies:** stage 14 and real authentication tests. Existing `@WithMockUser` principals will need matching database users when ownership resolution is introduced.

## Stage 16 — Better JWT security

**Outcome:** token behavior is deliberate and verifiable.

- [ ] Centralize and validate issuer, audience, permitted signing algorithm, lifetime, and key configuration. Use a stable user identifier; document the transition for existing username-subject tokens.
- [ ] Keep keys outside source control and images. Define key rotation and what happens to outstanding tokens when a user is disabled or roles change.
- [ ] Test actual issued tokens, not only mock JWTs: valid, expired, tampered, wrong issuer/audience, and unsupported algorithm cases.
- [ ] Define logout semantics. Add persisted, hashed refresh tokens with rotation/reuse detection only if persistent sessions are required; otherwise document access-token expiry behavior.
- [ ] Add bounded login/register abuse protection before public deployment. Revisit CORS and CSRF according to the chosen browser/token transport.

**Done when:** issuance and validation agree, expiry metadata matches the token, invalid tokens return 401, and rotation/session behavior has tests. Choose a managed identity provider if full identity lifecycle becomes a product requirement.

**Reference:** [Spring Security JWT validation and configuration](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Stage 17 — Dynamic querying

**Outcome:** optional filters compose without an expanding collection of repository methods.

- [ ] Refactor stage 13 filters into Spring Data JPA specifications and extend the repository with `JpaSpecificationExecutor`.
- [ ] Compose owner scope with optional status, priority, and title predicates. Add date predicates only after audited timestamps exist in stage 18.
- [ ] Keep pagination, validation, and DTO responses stable. Retire or delegate old search/status routes through the same query path.

**Done when:** filter combinations, zero results, invalid input, and owner isolation pass PostgreSQL integration tests. Avoid introducing a custom query language for a small fixed filter set.

**Dependencies:** 13 and 15. [Spring Data JPA specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html) support reusable predicates.

## Stage 18 — Auditing

**Outcome:** task changes have useful creation/modification metadata.

- [ ] Add `createdAt` and `updatedAt` as UTC instants with a migration/backfill policy for existing records.
- [ ] Enable JPA auditing. Add `createdBy`/`updatedBy` and `AuditorAware` if actor tracking is required; ownership and change actors have different meanings.
- [ ] Expose documented timestamps in DTOs. Define a system identity for background updates.
- [ ] If simultaneous editing matters, add optimistic locking with `@Version` and a conflict response as a separate increment.

**Done when:** creation time stays fixed, modification time changes on writes, and JWT/test/background identities behave correctly. Timestamp auditing is distinct from a permanent change-history log; add a history table only if needed.

**Reference:** [Spring Data JPA auditing](https://docs.spring.io/spring-data/jpa/reference/auditing.html).

## Stage 19 — PostgreSQL optimization

**Outcome:** performance changes follow measured query behavior.

- [ ] Seed a representative dataset and record list/search latency and query counts under a documented workload.
- [ ] Inspect owner-scoped filter/sort plans using `EXPLAIN (ANALYZE, BUFFERS)` in a test environment.
- [ ] Add justified indexes through Flyway, starting with actual owner/filter/sort patterns. Investigate a trigram index only if measured substring search warrants it.
- [ ] Address N+1 queries and unwanted lazy loading, measure count-query cost, and bound connection pool settings to deployment capacity.

**Done when:** before/after plans and measurements are recorded, correctness is preserved, and an agreed latency target is met on the stated dataset/hardware. Do not require an index scan when a sequential scan is the better plan.

**Dependencies:** stable queries and relationships from 13–18. [PostgreSQL 16 EXPLAIN documentation](https://www.postgresql.org/docs/16/using-explain.html).

## Stage 20 — Error architecture

**Outcome:** clients receive a consistent, actionable error contract.

- [ ] Extend the existing `ProblemDetail` design with stable application error codes, field errors, and a request correlation ID.
- [ ] Map validation, unreadable JSON, invalid enum/query input, authentication, access denial, missing resources, uniqueness conflicts, and unexpected exceptions intentionally.
- [ ] Configure authentication-entry-point and access-denied responses for security-filter errors; controller advice alone does not cover them.
- [ ] Handle duplicate-registration races at the database uniqueness boundary. Log unexpected errors with context while keeping responses free of stack traces and credentials.

**Done when:** 400/401/403/404/409/500 cases have contract checks; expected failures no longer fall through to a generic 500. Apply the essential mappings during baseline stabilization.

## Stage 21 — Advanced testing

**Outcome:** meaningful behavior remains protected as the application evolves.

- [ ] Retain fast service/mapper tests and MVC tests, and use PostgreSQL Testcontainers for database-specific behavior.
- [ ] Add real auth lifecycle tests alongside mock authentication, cross-user access tests, pagination/filter combinations, and transaction rollback assertions.
- [ ] Exercise migrations on an empty database and an upgrade fixture, including owner/audit backfills. Assert persisted state after mutations.
- [ ] Add concurrency tests where locking or uniqueness behavior requires them. Keep performance checks in a controlled job rather than timing-sensitive unit assertions.

**Done when:** a clean checkout can run the required suite without a personal database or `.env`; injected regressions in authorization, migrations, and token validation fail it. Add tests in each feature stage; this stage closes gaps rather than postponing testing.

**Build policy:** keep `./mvnw clean test` working. If integration tests later move to Maven Failsafe, document that change and require `./mvnw -B verify` in CI.

## Stage 22 — GitHub Actions CI

**Outcome:** every pull request gets a reproducible build signal.

- [ ] Create a workflow using the wrapper, JDK 21, Maven dependency caching, and a Docker-capable runner for the existing Testcontainers suite.
- [ ] Run `./mvnw -B verify`, retain useful test reports on failure, and configure the result as a required merge check.
- [ ] Use minimal workflow permissions, review action versions/pins, and add dependency/image scanning with a defined response policy.
- [ ] Build the release artifact once; stage 23 packages it into the image that later environments promote.

**Done when:** a deliberately failing test blocks the check and the normal branch passes from a clean checkout. Start a basic version during milestone A.

**Reference:** [GitHub Actions: building and testing Java with Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven).

## Stage 23 — Production Docker

**Outcome:** a tested artifact runs reliably with the production profile.

- [ ] Choose a reproducible multi-stage build or package the exact CI-tested JAR; remove ambiguous artifact copying. Keep build dependencies out of the runtime image.
- [ ] Run as a non-root user; add `.dockerignore`, runtime resource settings, image identity, and an update policy for the base image.
- [ ] Supply JWT/database settings externally. Use Flyway plus `ddl-auto=validate`, disable routine SQL logging, and support graceful shutdown.
- [ ] Smoke-test production configuration with Compose PostgreSQL, real login, and authenticated CRUD. Check persistence after container restart.

**Done when:** build and startup succeed from a clean checkout, the image contains no development secrets, and configuration failures are clear. Do not try to run Testcontainers inside an ordinary Docker build; run those tests in the CI job.

**Reference:** [Docker build best practices](https://docs.docker.com/build/building/best-practices/).

## Stage 24 — Monitoring

**Outcome:** an operator can detect and investigate failures.

- [ ] Extend the existing Actuator/custom counter with a metrics export path and dashboards for request rate, errors, latency, JVM, and database pool use.
- [ ] Use structured logs and request correlation. Avoid user IDs, task titles, and tokens as unbounded metric labels or sensitive log content.
- [ ] Configure health, readiness, and liveness access deliberately. The current security configuration permits only the exact `/actuator/health` path; nested probe routes need an explicit policy.
- [ ] Define a small set of service objectives and actionable alerts with response notes. Keep monitoring interfaces private or appropriately authenticated.

**Done when:** an injected application/database failure is visible and diagnosable. A database outage must not cause the liveness probe to restart every instance; readiness policy should reflect whether requests can be served.

**Reference:** [Spring Boot Actuator endpoints and probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).

## Stage 25 — API documentation / versioning

**Outcome:** developers and API consumers can use and change TaskFlow predictably.

- [ ] Write a README covering setup, Docker prerequisites, environment variable names, migrations, tests, and working auth/task request examples.
- [ ] Update OpenAPI for ownership, page envelopes, filters, security, and error responses as each feature lands.
- [ ] Document `/api/v1` compatibility and deprecation rules. Create v2 only for a deliberate breaking contract, rather than every release.
- [ ] Explain the difference between API contract version, application release version, and Flyway schema version. Add release, migration, and rollback notes.

**Done when:** another developer can follow the README from a clean checkout and every documented request matches behavior. Documentation starts in milestone A; this stage finishes the release contract.

## Stage 26 — Redis caching

**Outcome:** one measured expensive read becomes cheaper without compromising isolation.

- [ ] First record a cache-free baseline and choose a bounded use case. Skip this stage for the first release if PostgreSQL already meets the target.
- [ ] Use owner-aware cache keys and DTO values, explicit TTLs, serialization, and invalidation after committed writes. Keep authorization checks effective on cache hits.
- [ ] Define acceptable staleness, Redis outage behavior, and cache hit/miss monitoring. Add Redis Testcontainers coverage where useful.

**Done when:** users cannot receive each other's cached data, edits/deletes follow the documented freshness policy, and measurements demonstrate the benefit. Avoid caching arbitrary combinations of every page/filter initially.

**Dependencies:** 15, 19, and 24. [Spring Data Redis cache configuration](https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html).

## Stage 27 — Async / events

**Outcome:** a concrete side effect can run outside the request's main work.

- [ ] Choose a use case such as a task-completed notification. Keep core task persistence synchronous and transactional.
- [ ] Publish a domain event and run commit-dependent work after successful commit; use bounded execution and explicit failure reporting if running asynchronously.
- [ ] Document delivery guarantees. In-memory listeners are appropriate for disposable work; durable notifications require a persisted outbox/queue, retries, and idempotent processing.
- [ ] Carry the necessary actor/correlation context explicitly and monitor backlog/failure counts.

**Done when:** rolled-back changes do not trigger committed-outcome side effects, duplicate delivery is safe where retries exist, and restart/failure behavior matches the chosen guarantee.

**Reference:** [Spring transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html). Introduce a broker only when the use case needs it.

## Stage 28 — Cloud deployment

**Outcome:** the tested container runs in an environment that can be recovered and operated.

- [ ] Select one provider/region and a budget. Start with a managed container service and managed PostgreSQL unless operating Kubernetes is itself the objective.
- [ ] Configure image registry, HTTPS, external secrets, restricted database connectivity, least-privilege deployment identity, backups, and monitoring.
- [ ] Deploy the same tested image by digest. Make migration execution deliberate; use compatible schema changes so application rollback remains possible.
- [ ] Validate backup restoration in a separate environment and rehearse application rollback. Document that rolling an image back does not undo database migrations.

**Done when:** smoke tests pass over HTTPS, failures are observable, restoration and rollback have been demonstrated, and spending alerts/cleanup ownership exist. Choose provider-specific services and costs at implementation time.

**Dependencies:** 15, 16, and release gates 20–25. Redis/events are required only if enabled in the deployed application. Introduce stage 30 here for infrastructure intended to last.

## Stage 29 — Kubernetes

**Outcome:** learn orchestration or meet a demonstrated orchestration need.

- [ ] Start in a local learning cluster with Deployment, Service, configuration/secret integration, resource requests/limits, and startup/readiness/liveness probes.
- [ ] Add an appropriate ingress or gateway with TLS and a rolling-deployment policy. Keep PostgreSQL external for the initial exercise.
- [ ] Define migration ownership rather than letting deployment replicas run uncontrolled changes. Verify graceful termination and probe behavior.
- [ ] Exercise pod termination, scaling, and a failed rollout. Add autoscaling only when capacity metrics and workload justify it.

**Done when:** rollout and restart behavior preserve data, traffic only reaches ready instances, and secrets/resources have explicit access controls. This stage is optional for a useful deployed TaskFlow.

**Reference:** [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/). Base64 encoding is not encryption; use an intentional secret-storage and access policy.

## Stage 30 — Terraform

**Outcome:** the chosen cloud environment can be recreated and reviewed as code.

- [ ] Codify the selected network, database, runtime, registry, identity, and monitoring resources with environment-specific inputs.
- [ ] Use protected remote state with encryption, access control, and locking supported by the chosen backend. Keep state and secrets out of Git.
- [ ] Add formatting, validation, and reviewed plans to CI; make application of infrastructure changes controlled and explicit.
- [ ] Import any existing resources deliberately, review drift, and document recovery and teardown while protecting persistent data.

**Done when:** a disposable environment can be reproduced, a subsequent plan has no unexpected changes, and state recovery/teardown are documented. For real cloud delivery, do this alongside stage 28; keeping it last makes sense only as a learning sequence.

**Reference:** [Terraform sensitive data handling](https://developer.hashicorp.com/terraform/language/manage-sensitive-data). Marking a value sensitive controls display; it does not by itself remove the value from state.

## Working agreement for every stage

1. Define the behavior and acceptance checks before implementation.
2. Make a small vertical change across API, service, repository, migration, and documentation as needed.
3. Add tests for changed behavior; run the relevant checks, then the required suite before merging.
4. Review API compatibility, ownership scope, and database migration impact.
5. Finish with a working example and record decisions; do not start another infrastructure component to compensate for an unfinished feature.

Baseline stabilization and stages 13–14 are complete, following the requested numeric stage order. The next milestone is stage 15: assign creators from authenticated users, define treatment of legacy unassigned tasks, and enforce owner-scoped reads and writes. Until then, authenticated users still access a shared task dataset.

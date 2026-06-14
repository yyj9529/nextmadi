# Exec plan: Flyway schema and error response contract

## Goal

Implement issues #14 and #77 now that the backend scaffold and CI files exist:
add the v1 PostgreSQL schema as a Flyway migration and add a global Spring Boot
error response contract for `/api/v1/**`.

## Source specs

- GitHub issue #14: Flyway migrations for the full v1 schema.
- GitHub issue #77: backend global error response contract.
- `docs/data-model.md`: table, constraint, and index source of truth.
- `docs/api/openapi.yaml`: REST contract to update from the old error shape to
  the five-field contract.
- `docs/harness.md` section 6: error response body fields and debugging intent.
- `docs/architecture.md`: Flyway files under
  `backend/src/main/resources/db/migration/`, startup migration, forward-only
  rollback strategy.
- `SECURITY.md`: no secrets, no destructive SQL, no auth weakening, owner
  approval for dependency changes and DB migration commands.
- `docs/quality-gates.md`: backend/auth/DB gate.
- Existing backend scaffold plan:
  `docs/exec-plans/2026-06-14-backend-scaffold-ci.md`.

## Files expected to change

- Modify `backend/build.gradle`
  - Add Spring JDBC/Flyway/PostgreSQL runtime dependencies.
  - Add Testcontainers PostgreSQL dependencies for migration verification.
- Modify `backend/src/main/resources/application.yml`
  - Add datasource/Flyway configuration via environment variables only.
- Create `backend/src/main/resources/db/migration/V001__init_schema.sql`
  - Create the v1 tables, constraints, and indexes from `docs/data-model.md`.
- Create backend error-contract production files under
  `backend/src/main/java/com/phraselog/common/web/`
  - `ApiErrorException.java`
  - `ApiErrorResponse.java`
  - `GlobalExceptionHandler.java`
- Modify backend tests under `backend/src/test/java/com/phraselog/`
  - Keep scaffold health tests independent of a real datasource.
  - Add error-contract tests for 500 and representative 400/401/404/409/429.
  - Add migration tests against a clean PostgreSQL Testcontainers database.
- Modify `docs/api/openapi.yaml`
  - Replace the old `Error` schema with the five-field contract.
  - Ensure reusable 4xx/5xx response components point at that schema.
- Update this plan's final outcome after verification.

## Acceptance criteria

- Issues #9 and #10 exist before implementation starts.
- Flyway has a versioned migration that creates the v1 schema in dependency-safe
  order.
- Schema covers all 15 tables in `docs/data-model.md`:
  `users`, `coach_profiles`, `landing_examples`, `anonymous_analysis_usage`,
  `tts_audio_cache`, `ai_request_logs`, `user_auth_identities`,
  `analysis_requests`, `practice_sessions`, `expressions`,
  `expression_variants`, `review_cards`, `review_attempts`, `practice_turns`,
  plus the delayed FK constraints needed for circular references.
- Required constraints/indexes from #14 are present:
  input length check, expression source check, variant order uniqueness/check,
  review-card uniqueness and due partial index, practice-session status/planned
  turn checks, turn uniqueness, TTS cache uniqueness, anonymous usage composite
  PK, GIN search indexes, and soft-delete columns.
- Any unhandled `/api/v1/**` exception returns the contract shape with status
  500.
- Representative 400/401/404/409/429 responses carry:
  `error_code`, `user_message`, `developer_hint`, `retryable`,
  `request_correlation_id`.
- `request_correlation_id` in the error body matches the correlation header/MDC.
- No raw user text, secrets, or exception messages are exposed in
  `developer_hint`.
- OpenAPI documents the same error contract.
- Rollback path is documented here: migrations are forward-only; production
  rollback is RDS point-in-time restore or a compensating migration, never
  destructive ad-hoc SQL.

## Test plan

TDD order:

1. Write `ErrorContractControllerAdviceTests` first.
   - Expected red: compile failure because `ApiErrorException` /
     `ApiErrorResponse` / `GlobalExceptionHandler` do not exist yet.
2. Write `FlywayMigrationTests` first.
   - Expected red: compile or runtime failure because Flyway/PostgreSQL test
     dependencies and the migration file do not exist yet.
3. Implement the minimal error-contract classes and handler.
4. Implement the Flyway dependencies, datasource config, and migration SQL.
5. Run the smallest backend test command:
   `.\backend\gradlew.bat -p backend test --no-daemon`.
6. Run the backend gate:
   `.\backend\gradlew.bat -p backend spotlessCheck test build --no-daemon`.
7. Run OpenAPI validation if a repo tool/script exists.
8. Run `git diff --check`.

## Risk areas

- This task touches DB migration and error behavior, so the risky-change handoff
  rule applies after implementation.
- Adding dependencies requires owner approval under `SECURITY.md`.
- Running Flyway against a real database requires owner approval. Local tests may
  run Flyway against a disposable Testcontainers PostgreSQL database only.
- `docs/data-model.md` contains two circular/forward FK cases:
  `users.selected_coach_id -> coach_profiles`, and
  `practice_sessions.expression_id -> expressions`. The migration will create
  those columns first and add the FK constraints after the referenced tables
  exist.
- Do not run destructive SQL. Do not add down migrations that drop tables. Do not
  weaken auth or validation to make tests pass.
- Testcontainers may require Docker. If Docker is unavailable locally, report
  that the migration integration test could not execute here and rely on CI or
  an owner-approved local Docker run.

## Decision log

- Use one initial migration, `V001__init_schema.sql`, because the schema has not
  been released yet and #14 asks for the full v1 schema as the first Flyway
  baseline.
- Keep migrations forward-only, matching `docs/architecture.md`.
- Keep error body as a Java record so JSON field names are explicit and small.
- Use a project-specific `ApiErrorException` for known domain/client failures;
  feature tickets can reuse it instead of redefining response shapes.
- Use generic developer hints for unhandled exceptions to avoid leaking raw
  exception messages, user text, or secrets.

## Final outcome

- Issues #9 and #10 were confirmed to exist before implementation.
- Added Flyway/PostgreSQL wiring:
  - `backend/build.gradle` now includes Spring JDBC, Flyway, PostgreSQL, and
    Testcontainers dependencies.
  - `application.yml` defines Flyway migration locations and keeps Flyway
    disabled by default for local scaffold startup without DB env.
  - `application-prod.yml` enables Flyway by default and reads DB connection
    settings from env/secrets-provided values.
- Added the initial forward-only schema migration:
  `backend/src/main/resources/db/migration/V001__init_schema.sql`.
- Added the global backend error response contract:
  - `ApiErrorException`
  - `ApiErrorResponse`
  - `GlobalExceptionHandler`
- Updated `docs/api/openapi.yaml` so `Error` requires:
  `error_code`, `user_message`, `developer_hint`, `retryable`,
  `request_correlation_id`.
- TDD evidence:
  - First backend test run failed during `compileTestJava` because Flyway,
    Testcontainers, `ApiErrorException`, and `GlobalExceptionHandler` did not
    exist yet.
  - After implementation, `.\backend\gradlew.bat -p backend test --no-daemon`
    passed.
- Backend gate passed:
  `.\backend\gradlew.bat -p backend spotlessCheck test build --no-daemon`.
- OpenAPI validation available in this repo was limited to YAML parsing via
  local `js-yaml`; that check passed and confirmed the error schema required
  fields plus reusable response refs.
- Local limitation:
  - Docker is not installed on this machine (`docker` command not found), so
    `FlywayMigrationTests.cleanPostgresDatabaseMigratesToFullV1Schema` was
    skipped by Testcontainers.
  - `MigrationSqlStructureTests` still verifies the migration file contains the
    required tables, constraints, partial indexes, GIN indexes, and soft-delete
    columns. Run the Testcontainers migration test in CI or a local environment
    with Docker to prove `flyway migrate` against a clean PostgreSQL database.

## What changed after execution

- The plan expected the migration integration test to run locally, but the local
  machine has no Docker CLI/runtime available. The test now uses
  `@Testcontainers(disabledWithoutDocker = true)` and a separate static SQL
  structure test keeps schema coverage visible in Docker-less runs.
- The existing app-context scaffold tests now exclude JDBC/Flyway
  auto-configuration so `/actuator/health` remains testable without requiring a
  local database.

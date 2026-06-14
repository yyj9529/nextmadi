# Exec plan: backend scaffold and CI

## Goal

Build the backend foundation required by issues #9 and #10: a Spring Boot 3.x
Gradle scaffold with health, request correlation logging, systemd startup
material, and a PR CI workflow that runs frontend and backend gates.

This must land before backend feature tickets #14, #77, #28, #27, #26, #39, #65,
#29, #30, #59, #60, and #62.

## Source specs

- GitHub issue #9: Spring Boot scaffold and EC2 systemd startup.
- GitHub issue #10: PR CI for lint, test, and build.
- `docs/architecture.md`: Spring Boot on EC2, Java 21, Spring Boot 3.x, Tomcat,
  `/api/v1` convention, JSON stdout logs, CloudWatch collection, systemd deploy.
- `docs/api/openapi.yaml`: internal Next.js BFF to Spring Boot contract; browser
  callers do not call Spring Boot directly.
- `SECURITY.md`: no secrets, no deploy, no CI secret changes, no dependency
  install without owner approval.
- `docs/quality-gates.md`: backend tests, CI lint/test/build, no secret exposure.
- ADR-005 and ADR-010: Spring Boot stack and BFF auth handoff.

## Files expected to change

- Create `backend/settings.gradle`
- Create `backend/build.gradle`
- Create `backend/gradle.properties`
- Create `backend/src/main/java/com/phraselog/PhraselogBackendApplication.java`
- Create `backend/src/main/java/com/phraselog/common/web/RequestCorrelationFilter.java`
- Create `backend/src/main/resources/application.yml`
- Create `backend/src/main/resources/application-prod.yml`
- Create backend tests under `backend/src/test/java/com/phraselog/`
- Create `backend/deploy/systemd/phraselog-backend.service`
- Create `backend/deploy/systemd/README.md`
- Add Gradle wrapper files under `backend/`
- Create `.github/workflows/lint-test.yml`
- Update this plan's final outcome after verification

## Acceptance criteria

- Backend app starts as a Spring Boot 3.x Java 21 service.
- `/actuator/health` is available at the root management path.
- MVC/API base path is configured as `/api/v1` without adding feature endpoints.
- Every request gets a `X-Request-Correlation-Id` response header and an MDC
  value named `request_correlation_id`.
- Console logging is configured for Spring Boot structured Logstash JSON.
- Production profile can import AWS Secrets Manager config through a configurable
  `PHRASELOG_SECRETS_IMPORT` value without committing secrets.
- systemd unit includes automatic restart and stdout/stderr journald routing.
- CI runs frontend `bun run lint`, `bun run typecheck`, `bun run build`.
- CI runs backend `spotlessCheck`, tests, and Gradle build.
- No feature endpoints, DB migrations, deployments, secret access, or CI secret
  changes are included.

## Test plan

TDD scope:

1. Add backend tests first:
   - app context loads.
   - `/actuator/health` returns HTTP 200.
   - `spring.mvc.servlet.path` is `/api/v1`.
   - request correlation filter propagates provided IDs, generates missing IDs,
     sets the response header, and cleans MDC.
2. Run the backend test command after the scaffold exists. Start with offline
   mode if possible; request approval before any dependency download.
3. Run `bun run lint`, `bun run typecheck`, and `bun run build` because CI changes
   can affect frontend gates.
4. Run `git diff --check` before handoff.

## Risk areas

- Dependency download: local verification may need Maven/Gradle network access.
  That requires owner approval per `SECURITY.md`.
- External acceptance: ALB HTTPS, CloudWatch Logs collection, branch protection,
  and actual EC2 systemd restart are not locally verifiable without deploy/admin
  actions. This diff can prepare the repo files, but owner-managed infrastructure
  remains outside scope.
- Spring Cloud AWS Secrets Manager: production profile must not access local
  secrets during tests. Keep it profile-gated and optional.
- Scope creep: do not implement any OpenAPI feature endpoint in this scaffold.

## Decision log

- Use Spring Boot 3.5.15 because the repo requires Spring Boot 3.x and the
  official Spring Boot 3.5 docs list 3.5.15 as the current 3.5 stable line.
- Use Spring Boot built-in structured logging (`logging.structured.format.console`)
  instead of adding logstash-logback-encoder.
- Configure MVC base path with `spring.mvc.servlet.path=/api/v1` so actuator
  health remains available at `/actuator/health`.
- Use Spring Cloud AWS Secrets Manager starter only in the scaffold and keep the
  import profile-gated via `PHRASELOG_SECRETS_IMPORT`.
- Put systemd material under `backend/deploy/systemd/` as deploy-ready reference,
  not an executed deploy.

## Final outcome

- Backend scaffold created under `backend/` with Spring Boot 3.5.15, Java 21,
  Gradle wrapper 8.13, actuator health, structured JSON console logging, request
  correlation MDC/header handling, and prod-profile Secrets Manager config import.
- systemd startup material added under `backend/deploy/systemd/`.
- CI workflow added at `.github/workflows/lint-test.yml` with frontend and
  backend jobs.
- TDD evidence:
  - First backend test run failed at compile time because
    `RequestCorrelationFilter` did not exist.
  - After implementation, `.\backend\gradlew.bat -p backend test --no-daemon`
    passed.
- Backend gate passed:
  `.\backend\gradlew.bat -p backend spotlessCheck test build --no-daemon`.
- Frontend gates passed:
  `bun run lint`, `bun run typecheck`, `bun run build`.
- External acceptance still needs owner/infrastructure action:
  - `/actuator/health` via ALB HTTPS.
  - CloudWatch Logs collection from journald/stdout.
  - systemd restart behavior on EC2.
  - GitHub main branch protection attaching the new CI check.

## What changed after execution

- Initial plan tried `spring.mvc.servlet.path=/api/v1`, but that moved actuator
  under the servlet path and broke the issue #9 requirement that
  `/actuator/health` be available at the root management path. The scaffold now
  keeps `/api/v1` as `ApiPaths.V1` plus `phraselog.api.base-path`, leaving
  feature controllers to opt into that convention when they are implemented.
- `bun run build` temporarily changed the generated route import in
  `next-env.d.ts`; that incidental change was reverted because it is unrelated
  to backend scaffold/CI.

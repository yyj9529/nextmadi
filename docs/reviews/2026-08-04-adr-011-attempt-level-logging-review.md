# Review: ADR-011 attempt-level ai_request_logs

- Target: PR #130 / branch `docs/ai-request-logs-attempt-level` / `git diff main...HEAD`
- Reviewer: Codex
- Author: Claude Code
- Related exec-plan: `docs/exec-plans/2026-08-02-ai-request-logs-attempt-level.md`
- Date: 2026-08-04

## Gate 1 - Spec compliance

- [x] Stated acceptance criteria satisfied.
- [x] Honors the AI output schema in `AI_PIPELINE.md`.
- [x] No unrequested behavior beyond the exec-plan and its execution notes.
- [x] Error responses follow the existing contract; this change does not alter response shape.

Findings: 없음.

검토 근거:

- `backend/src/main/java/com/phraselog/ai/client/service/AnthropicService.java:87` creates one
  `attemptGroupId` per logical Claude call, and `:100-199` logs each attempt with dense
  `attemptNumber` and exactly one final row. This covers acceptance criteria 1-3.
- `backend/src/main/java/com/phraselog/ai/client/service/AnthropicService.java:103-159` preserves
  schema-validation response tokens for the failed row, while transport failures leave tokens and
  cost `NULL`. This matches `AI_PIPELINE.md` cost/null semantics and acceptance criteria 2-4.
- `backend/src/main/java/com/phraselog/ai/logging/dto/AiRequestLogEntry.java:81` and
  `:173-188` default one-shot STT/TTS/cache-hit callers to `attemptNumber=1`,
  `isFinalAttempt=true`, and a fresh `attemptGroupId`, matching acceptance criterion 5 without
  touching the one-shot services.
- `backend/src/main/java/com/phraselog/analysis/repository/JdbcAnalysisRepository.java:134-143`
  filters `findLogIdByCorrelation` to `is_final_attempt`, covering the query break called out in
  the exec-plan.
- `backend/src/main/resources/db/migration/V008__ai_request_logs_attempt.sql:6-21` adds the three
  columns, backfills old rows with `attempt_group_id = id`, enforces non-null/positive numbering,
  and adds `idx_logs_attempt_group`, covering acceptance criteria 6-7.
- `docs/AI_PIPELINE.md:221-263`, `docs/data-model.md:444-526`,
  `docs/architecture.md:314-356`, `docs/PRD.md:44-61`, and `SECURITY.md:50-56` were updated to
  the per-attempt logging and cost-measurement contract required by ADR-011.

## Gate 2 - Code quality

- [x] No needless abstraction; implementation stays inside existing `AiRequestLogEntry`,
  `JdbcAiRequestLogStore`, and `AnthropicService` seams.
- [x] Tests exist and target the risky behavior: retry success/failure, schema-token cost,
  timeout null cost, cache-hit zero cost, final-attempt lookup, V008 SQL shape, and privacy guard.
- [x] Auth, cost, logging, and error handling are safe under `SECURITY.md`; the new fields are
  metadata only.
- [x] No secret exposure; no raw user text added to committed artifacts.
- [x] Migration reviewed and rollback path documented below.

Findings: 없음.

Verification run:

- Ran from this checkout: `cd backend && ./gradlew spotlessCheck test build`
- First attempt failed before tests because the sandbox blocked Gradle wrapper download.
- Reran the same gate with network approval for the Gradle 8.13 download.
- Result from local JUnit XML: 389 tests, 309 passed, 0 failed, 0 errors, 80 skipped.
- Skipped locally: `OpenAiTranscriptionLiveTest` 1, `JdbcAiRequestLogStoreIntegrationTest` 6,
  `JdbcAnalysisRepositoryTests` 11, `OAuthIdentityControllerIntegrationTests` 3,
  `OAuthIdentityServiceTests` 5, `JdbcCoachRepositoryTests` 2, `FlywayMigrationTests` 1,
  `JdbcExpressionRepositoryTests` 14, `JdbcLandingExampleRepositoryTests` 3,
  `JdbcPracticeRepositoryTests` 3, `JdbcPracticeTurnRepositoryTests` 4,
  `JdbcReviewRepositoryTests` 5, `JdbcTtsAudioCacheRepositoryIntegrationTest` 8,
  `JdbcAnonymousAnalysisUsageRepositoryTests` 5, `JdbcUsageRepositoryTests` 3,
  `JdbcUserRepositoryTests` 6.
- `git diff --check main...HEAD` produced no whitespace errors.

Rollback path:

- `V008` is additive plus a deterministic backfill. Before production release, rollback is:
  deploy the previous backend version, then apply a compensating migration that drops
  `idx_logs_attempt_group`, drops `chk_ai_request_logs_attempt_number`, and drops
  `is_final_attempt`, `attempt_number`, and `attempt_group_id`.
- After production traffic has written per-attempt rows, do not run ad-hoc destructive SQL. Use an
  owner-approved compensating migration together with app rollback; use RDS point-in-time restore
  only if data reconciliation is required.

## Verdict

`pass`.

Matching gates:

- Backend / auth / DB change: passed locally with the skip caveat above. Migration is reviewed,
  rollback path is documented here, and no secret exposure was found.
- AI cost/logging change: passed. Cost now sums every attempt row, `is_final_attempt` preserves
  request-count semantics, and `NULL` vs `0` cost semantics are documented and tested.

## Notes for the owner

- This is still a `SECURITY.md` approval-path change: DB migration plus AI cost-measurement
  semantics. This review does not replace owner approval before merge/deploy.
- Local Docker/Testcontainers coverage did not execute in my environment. The local command passed,
  but CI or a Docker-enabled local run remains the evidence for live PostgreSQL migration behavior.
- Untracked file excluded from this review scope: `docs/agent-prompts/2026-08-04-pr-130-attempt-level-logging-review.md`.

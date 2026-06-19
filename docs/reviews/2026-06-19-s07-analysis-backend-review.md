# Review - S07 analysis backend (#39)

- **Date:** 2026-06-19
- **Reviewer:** Codex (independent initial review; follow-up fix applied in the same
  session at owner request)
- **Scope:** branch `feat/s07-analysis-backend` - `backend/src/main/java/com/phraselog/analysis/*`,
  `backend/src/main/resources/db/migration/V002__analysis_idempotency_key.sql`, and
  `backend/src/test/java/com/phraselog/analysis/*`.
- **Context read:** `docs/exec-plans/2026-06-19-s07-analysis-backend.md`,
  `docs/screens/s07.md`, `docs/api/openapi.yaml`, `docs/AI_PIPELINE.md`,
  `docs/data-model.md`, `SECURITY.md`, `docs/quality-gates.md`, and the prior
  review format in `docs/reviews/2026-06-18-internal-auth-jws-review.md`.
- **Verdict:** **PASS after follow-up fix.** The original review found one blocking
  issue: the production `POST /analysis` success path was missing the
  `schemas/s07_analysis_v1.json` resource loaded by `callClaude`. That blocker was
  fixed in this checkout by adding the classpath schema and regression tests for both
  `JsonSchemaValidator` and the `AnthropicService.callClaude` S07 success path. The
  #39 controller/service/repository shape matches the POST/GET contract,
  ownership/404 rule, sequential idempotency, and anonymous usage-limit ordering.

---

## Gate - `docs/quality-gates.md`

| Criterion | Result | Evidence |
|---|---|---|
| S07 schema validates | PASS | `backend/src/main/resources/schemas/s07_analysis_v1.json` exists and is covered by `JsonSchemaValidatorTests` plus an `AnthropicService` success-path test. |
| Service unit + controller tests pass | PASS | `.\gradlew.bat test --tests "com.phraselog.analysis.*"` passed. |
| Full backend test suite passes | PASS with skipped DB checks | `.\gradlew.bat test` passed, but Docker-gated JDBC/Flyway suites were skipped locally. |
| DB migration reviewed; rollback path documented | PASS | V002 is additive and matches `data-model.md`; rollback procedure is documented below in Finding 2. |
| Idempotency preserved where claimed | PARTIAL | Sequential retry is covered; concurrent same-key submissions can still double-call the LLM before the unique index rejects the second insert, which the exec-plan explicitly leaves out of scope. |
| Error response follows contract | PASS | `ApiErrorException` paths use the five-field global handler; missing/not-owned GET returns 404. |
| No secret/raw text exposure | PASS | No secrets added; request text is persisted in `analysis_requests` as product data, not logged to AI logs or test artifacts. |

---

## Findings - spec compliance

1. **(fixed) Missing `s07_analysis_v1` schema resource made every real analysis fail after the LLM call.**
   `AnalysisService.runAnalysis()` calls `anthropicService.callClaude(AiFeature.S07_ANALYSIS, prompt, inputText, userId, correlationId)` (`AnalysisService.java:121-123`), and the exec-plan treats that as the component that validates exactly three expressions. The real validator loads `schemas/{schemaId}.json` and throws if the file is missing (`JsonSchemaValidator.java:52-56`). At original review time there was no `backend/src/main/resources/schemas/` directory and the built classpath had only `db/` and `prompts/`.

   Impact: a live `POST /analysis` can spend the Anthropic call, then fail with `schema_validation_failed`, insert no `analysis_requests` row, and never render S07. This violates `AI_PIPELINE.md` schema validation, `quality-gates.md` S07 schema gate, and the #39 goal.

   Follow-up fix: added `backend/src/main/resources/schemas/s07_analysis_v1.json`, `JsonSchemaValidatorTests`, and an `AnthropicServiceTests` success-path regression test that uses the real validator.

2. **(documented) V002 rollback procedure.**
   `docs/quality-gates.md` requires DB migration review plus rollback documentation for backend/DB changes. V002 is safe and additive. If rollback is needed before release, drop `uq_analysis_idem_user`, drop `uq_analysis_idem_session`, then drop `analysis_requests.idempotency_key`.

## Findings - code quality

3. **(minor / accepted risk) Same-key concurrent submissions can still double-bill.**
   The implementation correctly handles the lost-response retry case by looking up `(caller, idempotency_key)` before the usage reservation and by catching `DuplicateKeyException` after insert. But two simultaneous first submissions can both miss the lookup and both call Claude before one insert loses. The exec-plan calls this out as out of scope, so this is not a merge blocker for v1 if the owner accepts the trade-off. It should not be described as full duplicate-billing prevention under concurrency.

---

## Edge cases verified handled

- POST returns 201 and maps the `AnalysisRequest` response shape.
- `input_text` blank / over 500 and missing or malformed `Idempotency-Key` return validation errors before provider work.
- Anonymous third daily attempt returns 429 before the LLM; authenticated users bypass anonymous usage.
- Provider/schema failure inserts no row and releases the anonymous slot.
- Sequential idempotent retry returns the existing row without a second provider call.
- GET returns the row only for the owner and returns 404 for non-owner, missing, and malformed IDs.
- V002 adds `idempotency_key` plus per-caller partial unique indexes matching `data-model.md`.

## Edge cases not fully verified

- `JdbcAnalysisRepositoryTests` were present but all 7 were skipped because Docker/Testcontainers is unavailable in this local environment.
- `FlywayMigrationTests` clean-Postgres migration was skipped for the same reason.
- No live Anthropic/OpenAI call was made, per `SECURITY.md`.
- The broader #26 runtime path has additional pre-existing risks outside this review scope: timeout/retry behavior and token/cost logging still need their own verification before treating the AI pipeline as production-ready.

---

## Verification run

- `cd backend && .\gradlew.bat test --tests "com.phraselog.analysis.*"`: passed.
- `cd backend && .\gradlew.bat test --tests "com.phraselog.ai.client.*"`: passed after adding the schema resource and regression tests.
- `cd backend && .\gradlew.bat test --rerun-tasks`: passed; Docker-gated JDBC/Flyway tests skipped.
- `cd backend && .\gradlew.bat spotlessCheck`: passed.
- `git diff --check`: passed.
- `Test-Path build/resources/main/schemas/s07_analysis_v1.json`: true.

## Disposition

- **Fixed in this checkout:** Finding 1.
- **Documented in this review:** Finding 2 rollback procedure.
- **Accepted if owner agrees:** Finding 3 concurrency billing risk, already called out by the exec-plan as out of scope.
- Final merge decision remains with the owner.

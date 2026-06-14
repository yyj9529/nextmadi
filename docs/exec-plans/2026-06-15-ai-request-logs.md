# Exec plan: ai_request_logs logging module

## Goal

Implement issue #27 (E04.2): a shared backend module that records cost and
latency metadata for every AI/STT/TTS call into `ai_request_logs`, on both
success and failure paths. This module is the dependency for the pipeline
tickets #26, #29, #30, #39, #60, #62, which will call it rather than write to the
table directly.

Scope is the logging module only. No live provider calls, no new endpoints, no
schema migration — the `ai_request_logs` table already exists from V001.

## Source specs

- GitHub issue #27: required fields, status/error enums, acceptance criteria.
- `docs/data-model.md` (`ai_request_logs`): column types, status check
  constraint, `feature_name` enumerated values, correlation grouping intent.
- `docs/AI_PIPELINE.md` ("Logging contract", "Cost and latency expectations"):
  required-fields-per-call-type, verified pricing table, model identifiers.
- `SECURITY.md` ("Logging discipline"): metadata-only, never raw user content.

## Key facts established before coding

- `ai_request_logs` already exists in `V001__init_schema.sql` with a
  `chk_ai_request_logs_status` check on `('success','error','timeout','cache_hit')`.
  No migration needed.
- Backend uses `spring-boot-starter-jdbc` (no JPA). Persistence is JdbcTemplate.
- `PhraselogBackendApplicationTests` excludes `DataSourceAutoConfiguration` so the
  app context loads without a DB. The logging beans must NOT break that context.
- This machine has no Docker (per 2026-06-14 plan), so Testcontainers integration
  tests are `disabledWithoutDocker` locally and run in CI. Pure-JVM unit tests
  cover the logic that can run without a DB.

## Design

New package `com.phraselog.ai.logging`.

- `AiFeature` enum — the seven `feature_name` wire values from data-model.md.
- `AiRequestStatus` enum — `success | error | timeout | cache_hit`.
- `AiErrorCode` enum — `schema_validation_failed | provider_5xx | provider_429 |
  timeout | network | unknown`.
- `AiRequestLogEntry` — immutable record + builder. Carries ONLY the metadata
  columns. There is structurally no field for raw user text, transcript, prompt
  body, or response body — the "never log raw user text" guarantee is enforced by
  the type, not by runtime filtering. Compact-constructor validation: feature,
  status, modelName required; latencyMs >= 0; `error_code` required when status is
  `error`/`timeout`, and forbidden when `success`/`cache_hit`.
- `AiCostCalculator` (`@Component`) — computes `estimated_cost_usd` (BigDecimal,
  scale 6 to match `NUMERIC(10,6)`):
  - `llm(modelName, inputTokens, outputTokens)` — Sonnet $3/$15, Haiku $1/$5 per
    MTok; model resolved by known-id prefix.
  - `whisperBySeconds(audioSeconds)` — $0.006/min.
  - `ttsByCharacters(charCount)` — $15 / 1M chars (TTS-1 standard).
  - Pricing constants carry the AI_PIPELINE.md "verified 2026-05-23,
    planning-grade, re-verify before W4" provenance in comments.
- `AiRequestLogStore` — interface, `void save(AiRequestLogEntry)`.
- `JdbcAiRequestLogStore` — JdbcTemplate INSERT, explicit JDBC types for nullable
  columns and `uuid` columns (Types.OTHER).
- `NoOpAiRequestLogStore` — used when no DataSource is present (context test, local
  no-DB run); logs at WARN that the row was dropped.
- `AiLoggingConfiguration` — `@Bean AiRequestLogStore` chosen via
  `ObjectProvider<DataSource>.getIfAvailable()` (lazy resolution avoids the
  `@ConditionalOnBean` ordering pitfall with user components).
- `AiRequestLogger` (`@Component`) — the public facade pipeline tickets inject.
  Delegates to the store and swallows any persistence exception (logs WARN with
  the correlation id). Rationale: observability must never break the user-facing
  AI call path. This is a noted decision, surfaced to the owner.

## Files expected to change

- Create under `backend/src/main/java/com/phraselog/ai/logging/`:
  `AiFeature`, `AiRequestStatus`, `AiErrorCode`, `AiRequestLogEntry`,
  `AiCostCalculator`, `AiRequestLogStore`, `JdbcAiRequestLogStore`,
  `NoOpAiRequestLogStore`, `AiLoggingConfiguration`, `AiRequestLogger`.
- Create under `backend/src/test/java/com/phraselog/ai/logging/`:
  `AiCostCalculatorTests`, `AiRequestLogEntryTests`, `AiRequestLoggerTests`
  (pure JVM, run locally), `JdbcAiRequestLogStoreIntegrationTest`
  (Testcontainers, runs in CI / Docker).
- No change to `V001__init_schema.sql`, build.gradle (deps already present:
  jdbc, flyway, postgresql, testcontainers), or app config.

## Acceptance criteria (from #27)

- A row is written for `success`, `error`, `timeout`, and `cache_hit` cases.
- Calls within one pipeline execution share `request_correlation_id` and can be
  queried as a group.
- LLM rows carry `prompt_version`, `input_tokens`, `output_tokens`,
  `estimated_cost_usd`; STT/TTS rows leave `prompt_version` null.
- Failure rows carry an `error_code` from the enum.
- No raw user text is ever stored — enforced structurally by the entry type.

## Test plan (TDD order)

1. `AiCostCalculatorTests` — assert documented amounts: Sonnet 900in/270out =
   0.006750; Haiku 3000in/500out = 0.005500; Whisper 30s = 0.003000; TTS 80 chars
   = 0.001200. Expected red: classes do not exist.
2. `AiRequestLogEntryTests` — builder validation + reflective assertion that no
   record component name implies raw content (text/transcript/prompt/response/
   content/body/input/utterance beyond the allowed token-count fields).
3. `AiRequestLoggerTests` — records via a fake store; swallows a throwing store;
   no-ops on the no-op store.
4. `JdbcAiRequestLogStoreIntegrationTest` — Testcontainers Postgres + Flyway:
   one row per status; correlation grouping returns the 4 turn features; persisted
   textual columns contain only allowed metadata (raw-text sentinel never present).
5. Run unit tests locally:
   `.\backend\gradlew.bat -p backend test --no-daemon`.
6. Run the no-region backend gate (owner-specified):
   `AWS_REGION= AWS_EC2_METADATA_DISABLED=true ./gradlew spotlessCheck test build --no-daemon`.
7. `git diff --check`.

## Risk areas / guardrails

- `SECURITY.md`: no live API calls, no migration execution, no secret access, no
  dependency install — none are needed; deps already declared.
- The logging beans must keep the no-DB app context loading; verified by the
  existing scaffold context test staying green.
- Testcontainers needs Docker; if absent locally the integration test is skipped
  and CI is the source of truth for "row created" proof. Unit tests still prove
  cost/validation/facade logic locally.
- Codex is unavailable (no token), so the two-agent handoff is replaced by a
  self-review with `/code-review` in a fresh context after implementation.

## Decision log

- Persist via JdbcTemplate, not JPA — matches the existing stack (starter-jdbc).
- "Never log raw user text" is enforced by omitting any such field from
  `AiRequestLogEntry`, not by sanitizing strings at write time. A structural
  guarantee is stronger than a runtime filter.
- `AiRequestLogger` swallows persistence failures (WARN-logs them). Observability
  must not take down the AI call path. Surfaced to the owner as a noted trade-off.
- Store selection uses `ObjectProvider<DataSource>` rather than
  `@ConditionalOnBean`, which is unreliable for user-defined components relative to
  autoconfigured beans.
- Cost is computed at log time from the AI_PIPELINE.md pricing table, which is
  planning-grade and flagged for re-verification before/at W4. Unknown model ids
  yield a null cost (the column is nullable) plus a WARN, never a silent $0.

## Final outcome

- Branched `feat/ai-request-logs` from `origin/main` (507aac8) after confirming PR
  #80 was squash-merged; the local checkout had only stale main.
- Implemented the module under `com.phraselog.ai.logging`:
  `AiFeature`, `AiRequestStatus`, `AiErrorCode`, `AiRequestLogEntry` (+builder),
  `AiCostCalculator`, `AiRequestLogStore`, `JdbcAiRequestLogStore`,
  `NoOpAiRequestLogStore`, `AiLoggingConfiguration`, `AiRequestLogger`.
- No migration/build.gradle/config change: `ai_request_logs` and all required deps
  already exist from V001 / PR #80.
- Tests:
  - Unit (run locally): `AiCostCalculatorTests` (8), `AiRequestLogEntryTests` (7),
    `AiRequestLoggerTests` (4) — all pass.
  - Integration `JdbcAiRequestLogStoreIntegrationTest` (4) — Testcontainers,
    `disabledWithoutDocker`; skipped locally (no Docker), runs in CI.
- Gate passed (owner-specified no-region command):
  `AWS_REGION= AWS_EC2_METADATA_DISABLED=true ./gradlew spotlessCheck test build
  --no-daemon` → BUILD SUCCESSFUL.
- `git diff --check` clean.
- Local limitation: same as the 2026-06-14 plan — no Docker CLI on this machine, so
  the row-creation / correlation-grouping proof comes from CI, not a local run. The
  unit tests still prove cost math, entry validation, the structural no-raw-text
  guarantee (record-component assertion), and facade fail-open behaviour locally.

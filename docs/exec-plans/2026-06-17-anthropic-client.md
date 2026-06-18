# Exec plan: Anthropic client (routing / schema validation / fallback)

## Goal

Implement issue #26: one backend path for every Claude LLM call, keyed by
`feature_name`. The client loads the versioned prompt (#28), routes to the right
model/timeout, calls Anthropic, validates the JSON response against the per-feature
schema, applies the exact retry/fallback matrix from the handoff, surfaces failures
through the #77 error contract, and writes exactly one `ai_request_logs` row (#27)
for every terminal outcome.

Scope is the client module only. No new HTTP endpoints (those are #39/#60). This is
the runtime consumer of #28's `PromptDefinition` and #27's logging, and the
dependency that #39 (S07 backend) and #60 (turn pipeline) build on.

## Source specs

- GitHub issue #26: routing, schema validation, fallback, retry; runtime Sonnet/Haiku
  calls; no-raw-text guard; live call/cost behind a global `PermissionRequest`.
- `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md` — the confirmed design
  (routing table, schema rules, fallback matrix, implementation-test list). Not
  stale; matrix unchanged → no Opus 4.8 design re-review needed.
- `docs/AI_PIPELINE.md` — routing table (lines 96–104), schema definitions (lines
  110–211), logging contract (lines 213–240), timeout/fallback policy (lines
  242–258), and the verbatim constraint-reminder string (line 72).
- `docs/quality-gates.md` — "Backend / auth / DB change" gate (unit+integration
  pass, error contract, no secret exposure).
- `SECURITY.md` — live Anthropic call/cost is a global `PermissionRequest`; never log
  raw user content.

## Key facts established before coding

Verified against the repo and the claude-api skill, not assumed.

- **Existing infra to reuse — do NOT re-create.** Java 21 / Spring Boot 3.5.15,
  Gradle, Jackson + SnakeYAML already on classpath.
  - `com.phraselog.ai.logging`: `AiRequestLogger.log(AiRequestLogEntry)` (never
    throws), `AiRequestLogEntry.builder()`, enums `AiFeature` / `AiRequestStatus`
    (`SUCCESS|ERROR|TIMEOUT|CACHE_HIT`, `isFailure()` true for ERROR/TIMEOUT) /
    `AiErrorCode` (`SCHEMA_VALIDATION_FAILED|PROVIDER_5XX|PROVIDER_429|TIMEOUT|
    NETWORK|UNKNOWN`), `AiCostCalculator.llm(modelName, inTok, outTok) -> BigDecimal`
    (returns null + warns on unknown model; keyed by `claude-sonnet-4-6` /
    `claude-haiku-4-5` prefixes).
  - `com.phraselog.ai.prompt`: `PromptLoader.load(String path, int version) ->
    PromptDefinition` with `.body()`, `.model()`, `.promptVersion()`,
    `.outputSchema()`. Prompt path keys: `s07`, `roleplay/init`, `roleplay/turn`,
    `roleplay/feedback`, `roleplay/result`.
  - `com.phraselog.common.web`: `ApiErrorException(HttpStatus, errorCode,
    userMessage, developerHint, retryable)`; `RequestCorrelationFilter` MDC key for
    the correlation id.
- **`AiRequestLogEntry` has no field for raw text/transcripts/response bodies** — the
  no-raw-text guard is structural. Keep it that way; do not add such a field.
- **Model name is single-sourced from the prompt front-matter.** `PromptDefinition
  .model()` already returns `claude-sonnet-4-6` / `claude-haiku-4-5`, and
  `AiCostCalculator` keys on the same strings. The routing table must NOT redeclare
  the model — only timeout + prompt path + schema validator. This avoids two sources
  of truth drifting.
- **The official `anthropic-java` SDK is not yet in `backend/build.gradle`** — adding
  it is part of this ticket (D1).
- **The SDK auto-retries 429/5xx by default (≈2×)**, which conflicts with the exact
  matrix below. Disable it (`maxRetries(0)`) so our policy is the only retry owner
  (D1).
- **Handoff vs AI_PIPELINE disagree on 429.** Handoff: "retries at 1s and 3s" +
  explicit test "retries twice with 1s/3s schedule". AI_PIPELINE line 250: "Retry
  once with exponential backoff (1s, then 3s)". The handoff + its explicit test are
  the implementation source of truth → **two retries at 1s then 3s.** Flag the
  AI_PIPELINE wording for reconciliation in the review, do not change behavior.

## Decisions (confirmed by owner 2026-06-17)

- **D1 — Transport: official `anthropic-java` SDK, internal retry disabled.** Chosen
  over raw `RestClient`. The SDK's typed exceptions map 1:1 onto our `AiErrorCode`
  (429 → PROVIDER_429, 5xx → PROVIDER_5XX, connection-timeout → TIMEOUT, connection
  → NETWORK), and it extracts `usage` tokens for cost logging. Build the client bean
  with `maxRetries(0)`; our `RetryPolicy` is the sole retry owner. **Exact SDK class
  names (client builder, request params, typed exceptions, usage accessors) are
  resolved at implementation time from the SDK / the claude-api `java/` docs — not
  pre-specified here** (claude-api skill: never guess SDK symbols).
- **D2 — Schema validation: typed Java records + per-feature validators.** Chosen
  over JSON-Schema-file + networknt. No new dependency, matches the repo's
  record-heavy style, and expresses the conditional rules (feedback's
  show_feedback-gated required fields) naturally. Anthropic native structured outputs
  are intentionally NOT used in v1 — the handoff's contract is app-side validation +
  constraint-reminder retry, and structured outputs cannot enforce the semantic
  rules (length == 3, range 3–10). Note structured outputs as a possible later
  optimization; adopting it would be a handoff change requiring design review.
- **D3 — Provider seam for testability.** A single-method `AnthropicProvider`
  interface wraps the SDK call; the production impl does only the SDK call and
  rethrows typed exceptions. Routing, validation, retry, and logging sit above the
  seam, so the full path is unit-tested with a fake provider + fake clock — no
  WireMock, no live calls in CI.
- **D4 — Backoff via an injectable time abstraction.** A `Sleeper` (or `Clock`)
  seam lets tests assert the 500ms / 1s / 3s schedule deterministically with a fake.

## Design

New package `com.phraselog.ai.client` (parallel to `ai.logging`, `ai.prompt`).

- **`FeatureRouting`** — maps `AiFeature` → `{ timeout, PromptRef path, schema
  validator }`. Values from the handoff table: `s07_analysis` 30s / `s07`,
  `roleplay_session_init` 30s / `roleplay/init`, `roleplay_turn_response` 15s /
  `roleplay/turn`, `roleplay_turn_feedback` 10s / `roleplay/feedback`,
  `roleplay_result` 30s / `roleplay/result`. Model intentionally absent (comes from
  the loaded `PromptDefinition`).
- **Output records (Jackson-bound)** — `S07AnalysisOutput`,
  `RoleplaySessionInitOutput`, `RoleplayTurnResponseOutput`,
  `RoleplayTurnFeedbackOutput`, `RoleplayResultOutput`, shaped per AI_PIPELINE schema
  definitions.
- **`OutputSchemaValidator`** — per-feature semantic validation (handoff "Schema
  rules"):
  - Reject non-JSON / preamble before binding.
  - `s07_analysis.expressions.length == 3` exactly.
  - `roleplay_session_init.planned_turns` ∈ [3, 10].
  - `roleplay_turn_response.coach_utterance` non-empty.
  - `roleplay_turn_feedback`: when false, allow only `{"show_feedback": false}`; when
    true, require `natural_alternative` + `korean_comment`.
  - `roleplay_result`: allow empty `recommended_expressions` / `awkward_pairs` /
    `pronunciation_focus_words`; require `coach_encouragement`.
- **`AnthropicProvider`** (D3) — `call(params, timeout) -> RawLlmResponse`; SDK-backed
  impl + test fake.
- **`RetryPolicy`** — classifies provider outcomes to `AiErrorCode` and drives the
  matrix (handoff, SoT):
  - JSON/schema mismatch → append the constraint reminder once, retry once → on
    second failure `schema_validation_failed`.
  - 5xx → retry once after 500ms → `provider_5xx`.
  - 429 → retry at 1s, then 3s → `provider_429`.
  - timeout → **no retry** → `timeout`.
  - network → retry once after 500ms → `network`.
  - Constraint reminder = AI_PIPELINE line 72 verbatim, appended **exactly once**.
- **`AnthropicClient`** (entry point) — `invoke(feature, dynamicInputs, correlationId,
  userId) -> validated typed output`. Flow: load prompt (system = body) → build SDK
  params (model from `PromptDefinition.model()`, timeout + maxTokens from routing,
  dynamic inputs as messages) → `AnthropicProvider.call` → classify → validate →
  retry per policy → log → return. Final failure → `ApiErrorException` with the
  mapped `error_code` and `retryable` from the matrix's user action. UI copy stays a
  screen concern.
- **Logging** — on every terminal path (success/error/timeout) build one
  `AiRequestLogEntry` and call `AiRequestLogger.log()` **exactly once**, with the
  caller's `request_correlation_id`. Fields: feature, model, prompt_version,
  input/output tokens (SDK usage), `estimated_cost_usd` (`AiCostCalculator.llm`),
  status, error_code, `latency_ms` (total incl. retries). No raw text (structural).
- **Config bean** — `@Configuration` builds the SDK client with `maxRetries(0)`; API
  key from AWS Secrets Manager in prod (existing `application-prod.yml` import) and
  env var in dev; dummy default in test so CI never calls live.

## Files expected to change

- `backend/build.gradle` — add the `anthropic-java` SDK dependency (D1).
- `backend/src/main/resources/application.yml` / `application-prod.yml` — API key
  binding (Secrets Manager / env).
- New under `backend/src/main/java/com/phraselog/ai/client/`: `AnthropicClient`,
  `AnthropicProvider` (+ SDK impl), `FeatureRouting`, the 5 output records,
  `OutputSchemaValidator`(s), `RetryPolicy`, `Sleeper` seam, schema-validation
  exception, config bean.
- New tests under `backend/src/test/java/com/phraselog/ai/client/`.

## Tests

Unit (fake provider + fake clock, no live call):

- Each feature resolves the expected model, prompt path, timeout, and schema.
- Schema validation rejects missing required fields for all 5 schemas.
- Schema retry appends the constraint reminder **exactly once**.
- Provider 5xx retries once → `provider_5xx`.
- Provider 429 retries twice at 1s/3s on the fake clock.
- Timeout does not retry → `timeout`.
- Network error retries once after 500ms → `network`.

Integration:

- Every terminal status calls #27 logging **exactly once** with the same
  `request_correlation_id` passed into the client.

Run order: `cd backend && ./gradlew test --tests "com.phraselog.ai.client.*"`
first, then the full `./gradlew test` (build file changed).

## Out of scope / hand-off

- `POST /analysis` etc. (#39) and the S12 turn pipeline (#60) consume this client;
  not built here.
- Anonymous daily limit (#34), STT (#29), TTS (#30) are separate.
- No live model call and no eval run in CI — live call/cost is a global
  `PermissionRequest` per SECURITY.md, exercised only on the production path.

## Verification commands

- Start: `git status --short --branch` (fresh branch off `main`).
- Post-change: `cd backend && ./gradlew test` (client tests, then full suite).
- Pre-PR: `git diff --check`.

## Risks / notes

- D1 is load-bearing: if the SDK's internal retry is left on, the matrix is wrong
  (extra hidden retries, mis-timed backoff) while tests that mock the provider seam
  still pass. The `maxRetries(0)` config and the seam together are what make the
  matrix the single retry authority — verify the production bean sets it.
- The 429 wording mismatch (handoff vs AI_PIPELINE) is a doc inconsistency, not a
  behavior choice: implement two retries (1s, 3s) per the handoff + its explicit
  test, and flag AI_PIPELINE line 250 for a one-line reconciliation in the review.
- Per CLAUDE.md two-agent handoff, #26 is Codex-implemented; this plan is the
  hand-off medium (plan → Codex implement → independent review in `docs/reviews/`).
  No Fable5 eval review required; the gate is `docs/quality-gates.md` "Backend".

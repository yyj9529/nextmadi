# Exec plan: S07 analysis backend — POST /analysis + GET /analysis/{id} (#39)

Status: **Implemented in current checkout.** Owner approved both blocking decisions
before coding: (A) add `idempotency_key` to `analysis_requests` via Flyway V002, and (B)
retrieve `ai_request_logs.id` by `request_correlation_id` lookup. See "Final outcome".

## Goal

Implement issue #39: the core S07 analysis flow as an internal BFF-to-Spring contract.

- `POST /analysis` — accept a Korean situation (`input_text`, <= 500 chars), call the
  S07 LLM pipeline, validate exactly 3 expression variants, persist the analysis, and
  return 201 with the `AnalysisRequest` shape.
- `GET /analysis/{analysis_request_id}` — return the analysis to its owner only
  (`user_id` when authenticated, `session_token` when pre-signup); 404 otherwise, never
  403.

Anonymous (`session_token`) calls apply the #34 rate limit and record `ip_address`;
authenticated (`user_id`) calls are unlimited in v1. Same `Idempotency-Key` from the
same caller must not double-bill or double-insert.

## Source specs

- GitHub issue #39 + PR #78 handoff comment.
- `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md` (#39 section): POST/GET
  behavior, idempotency rules, implementation test matrix. **Primary handoff SoT.**
- `docs/screens/s07.md`: ownership/404 rule, tone-intent → variant 1, 3-card structure.
- `docs/AI_PIPELINE.md`: `s07_analysis_v1` schema; `s07_analysis` routes to
  `claude-sonnet-4-6`, 30s timeout, `prompts/s07/v{N}.md`.
- `docs/api/openapi.yaml`: `POST /analysis`, `GET /analysis/{id}`, `AnalysisRequest`,
  `ExpressionVariant`, `Error`, `IdempotencyKey`/`ClientIp` params, 400/404/429 responses.
- `docs/data-model.md` + `V001__init_schema.sql`: `analysis_requests` columns and CHECK.
- `SECURITY.md`: no live AI cost in tests; DB migrations need owner approval; never log
  raw user text.
- `docs/quality-gates.md`: backend/AI/auth gate — unit + integration tests, error
  contract, two-gate review.

## Key facts established before coding

The dependency building blocks already exist in the checkout (issues merged even where
the tracking issue is still OPEN):

- `AnthropicService.callClaude(feature, PromptDefinition, userContent, userId, correlationId)`
  returns the parsed `{ "expressions": [...] }` `JsonNode`, runs `s07_analysis_v1` schema
  validation internally (enforces exactly 3), and logs success/failure to
  `ai_request_logs` itself. It throws `ApiErrorException` (already mapped to the 5-field
  error contract) on provider/schema failure. **#39 is the only production caller.**
- `PromptLoader.load("s07", 1)` → `PromptDefinition` with `promptVersion() == "s07-v1"`
  (from front-matter) and the model-facing `body()`.
- `AnonymousAnalysisUsageService.withAnonymousAnalysisLimit(principal, clientIpHeader, Supplier<T>)`
  reserves before the work, releases on exception, and bypasses for authenticated users.
- `InternalAuthPrincipal` is read from `request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE)`;
  it carries exactly one of `userId`/`sessionToken` (both `String`), plus `isAuthenticatedUser()`.
- `ClientIpResolver` (header `X-Client-IP`) validates and normalizes the IP; the usage
  service already calls it internally for anonymous reservations.
- Error contract: throw `ApiErrorException(status, error_code, userMessage, devHint, retryable)`;
  `GlobalExceptionHandler` renders it. No `spring-boot-starter-validation` on the
  classpath, so request validation is **manual** (no `@Valid`/`@NotNull`), throwing
  `ApiErrorException` with `error_code = "validation_failed"`, status 400.
- DB access is `JdbcTemplate`; DB-backed beans are wired with
  `@ConditionalOnBean(JdbcTemplate.class)` (see `AnonymousAnalysisUsageConfiguration`,
  logging `JdbcAiRequestLogStore` vs `NoOpAiRequestLogStore`).
- `analysis_requests` columns: `id, user_id, session_token, ip_address INET, input_text,
  output_json JSONB, prompt_version, ai_request_log_id → ai_request_logs(id), created_at`,
  with `CHECK (length(input_text) <= 500)`. **There is no controller package yet — #39 is
  the first `@RestController`.**

Two gaps the schema/code do not currently cover (see Decisions):

1. **No idempotency storage.** `analysis_requests` has no `idempotency_key` column, and
   `architecture.md` lists idempotency only for Save Expression / Start Roleplay — not
   analysis. But #39, the handoff, and `openapi.yaml` all require `Idempotency-Key` on
   `POST /analysis`. Deduping retries requires persisting the key.
2. **`ai_request_logs.id` is not retrievable.** Logging happens inside `callClaude`;
   `AiRequestLogger.log()` is `void`, `AiRequestLogStore.save()` does not return the
   generated id, and `AiRequestLogEntry` has no id field. So the `ai_request_log_id` FK
   cannot be populated through the current logging API.

## Decision A — idempotency storage (BLOCKS coding; needs migration approval)

**Recommended: add `idempotency_key UUID` to `analysis_requests` (Flyway V002) with a
per-caller partial unique index.** Look up before doing any expensive work.

- `CREATE INDEX ... ON analysis_requests(user_id, idempotency_key) WHERE user_id IS NOT NULL`
  and an equivalent on `(session_token, idempotency_key) WHERE user_id IS NULL`, both
  unique. Caller identity = `user_id` (authed) or `session_token` (anon).
- POST flow: resolve principal + key → **look up existing row by (caller, key) first**.
  If found, return it without touching the rate limit or the LLM (satisfies "row created
  but response lost → retry must not call the LLM again"). If absent, proceed; on insert,
  a `DuplicateKeyException` (lost-response race) is caught and re-read to return the
  existing row.
- Satisfies all three handoff idempotency rules and the test "retry with same key does
  not double-bill or double-insert." A failed first attempt persists no key, so a retry
  may legitimately create the row.

Alternatives considered:
- **Separate `idempotency_keys` table** — more general, but over-engineered for one
  endpoint in v1; Save/Roleplay get their own idempotency later and need not share this.
- **No persistence / input-text hashing** — cannot honor an explicit `Idempotency-Key`
  and would wrongly dedupe legitimate re-submissions. Rejected.

This adds a migration → **owner approval required**. Also reconcile `architecture.md`
(add analysis to the idempotency list, or note the analysis-specific decision).

## Decision B — `ai_request_log_id` FK retrieval (BLOCKS coding)

**Recommended: after `callClaude` returns, look up `ai_request_logs` by
`request_correlation_id` to get the id, and store it as the FK (best-effort, nullable).**

- `#39` generates the `request_correlation_id` (one per analysis call) and passes it into
  `callClaude`, so it can query it back: `SELECT id FROM ai_request_logs WHERE
  request_correlation_id = ? ORDER BY created_at DESC LIMIT 1`. The log row is written
  synchronously inside `callClaude` before it returns, so the row exists by lookup time.
- Keeps #39 self-contained — no change to the merged #26/#27 contracts. If logging was
  swallowed or the no-DB store is active, the lookup returns nothing and the FK stays
  null (column is nullable). Graceful.

Alternative considered:
- **Thread the id back through the logging chain** (`save` → `UUID`, `log` → `UUID`,
  `callClaude` → a `{content, logId}` record). Cleaner and explicit, and #39 is the only
  caller today, but it modifies three merged shared classes across #26/#27 and widens the
  review surface. Defer unless the lookup proves fragile.

Note: `callClaude` currently logs null `input_tokens`/`output_tokens`/cost because the
client strips `usage` when it extracts the content block — a pre-existing #26 limitation,
out of #39 scope. Flag in the review, do not fix here.

## Decision C — response variant `id` (minor, no approval needed)

`ExpressionVariant.id` is `required`, but at analysis time no `expression_variants` rows
exist (those are created only on Save, #expressions). The Save flow keys off
`analysis_request_id + selected_variant_order`, not variant id, so the id is cosmetic
here. Derive it deterministically: `UUID.nameUUIDFromBytes((analysisId + ":" + order))`
so GET is stable across calls. `tts_audio_url` is omitted (null) at analysis stage.

## Design

### POST /analysis flow

1. Read verified `InternalAuthPrincipal` from the request attribute.
2. Manual validation: `input_text` present and `length <= 500` else 400
   `validation_failed`; `Idempotency-Key` present (UUID) else 400.
3. Idempotency lookup by (caller, key). If hit, map the stored row and return 201 with
   the existing analysis (no rate-limit, no LLM).
4. Wrap the protected work in `withAnonymousAnalysisLimit(principal, clientIpHeader, () -> {...})`:
   - Load prompt `("s07", 1)`.
   - Generate `correlationId`; call `callClaude(S07_ANALYSIS, prompt, input_text, userId, correlationId)`.
   - Look up `ai_request_log_id` by correlationId (Decision B).
   - Insert `analysis_requests` row: user_id/session_token/ip_address per caller,
     `output_json` = the returned node, `prompt_version` = `prompt.promptVersion()`,
     `ai_request_log_id`, `idempotency_key`.
   - Return the persisted row.
5. Map to `AnalysisResponse` (201). `withAnonymousAnalysisLimit` releases the reserved
   slot automatically if step 4 throws.

`userId` is `UUID.fromString(principal.userId())` for authed, null for anon.
`ip_address` is stored only for anonymous successful inserts.

### GET /analysis/{id} flow

- Authed: `SELECT ... WHERE id = ? AND user_id = ?`; anon: `... WHERE id = ? AND
  session_token = ?`. Missing/not-owned → 404 `not_found` (never 403). Map to
  `AnalysisResponse`.

### Response mapping

`AnalysisResponse { id, input_text, variants[3], prompt_version, created_at }`; each
`ExpressionVariant { id (Decision C), variant_order (1..3 by array index), tone_label,
english_text, ipa, korean_pronunciation, pronunciation_tip, cultural_tip, tts_audio_url=null }`
mapped from `output_json.expressions[i]`.

## Files expected to change

New `com.phraselog.analysis` package:
- `AnalysisController.java` — `@RestController`, POST/GET, principal + header extraction,
  manual validation.
- `AnalysisService.java` — orchestration (idempotency, usage wrap, callClaude, persist, map).
- `AnalysisRepository.java` (interface) + `JdbcAnalysisRepository.java` — insert, find by
  id+owner, find by (caller, key), find `ai_request_log_id` by correlation id.
- `AnalysisRequestRow.java` — persisted-row record.
- DTOs: `AnalysisResponse.java`, `ExpressionVariantDto.java`, `CreateAnalysisRequest.java`.
- `AnalysisConfiguration.java` — `@ConditionalOnBean(JdbcTemplate.class)` wiring for the
  repository (mirror the usage/logging pattern; document that the endpoint needs DB).

Migration (Decision A, on approval):
- `backend/src/main/resources/db/migration/V002__analysis_idempotency_key.sql`.

Docs:
- `docs/data-model.md` — add `idempotency_key` to `analysis_requests`.
- `docs/architecture.md` — reconcile the idempotency list to include analysis.

Tests (no live provider; fake `AnthropicService`/client):
- `AnalysisServiceTests` (unit, fake collaborators).
- `AnalysisControllerTests` (MockMvc).
- `JdbcAnalysisRepositoryTests` (Testcontainers PostgreSQL against real Flyway schema).

## Implementation sequence

1. (On approval) Write `V002` migration + update `data-model.md`/`architecture.md`.
2. `JdbcAnalysisRepositoryTests` → `AnalysisRepository`/`JdbcAnalysisRepository`
   (insert, find-by-id+owner, find-by-key, find-log-id-by-correlation).
3. `AnalysisServiceTests` → `AnalysisService` with fake `AnthropicService` returning a
   minimal valid 3-expression node; assert idempotency, usage wrap, persistence fields.
4. `AnalysisControllerTests` (MockMvc) → `AnalysisController` + DTOs + `AnalysisConfiguration`.
5. Run backend tests; typecheck unaffected (backend-only).

## Test plan (maps to handoff matrix)

- 400 when `input_text` blank or > 500 chars; 400 when `Idempotency-Key` missing.
- Anonymous third daily attempt → 429 before the LLM (fake provider invocation count 0).
- Authenticated request bypasses anonymous usage.
- Successful request persists `output_json`, `prompt_version = "s07-v1"`, and
  `ai_request_log_id` when the log row exists.
- Schema/provider failure surfaces through `callClaude`'s `ApiErrorException` mapping;
  no row inserted; anonymous slot released.
- Retry with same `Idempotency-Key` returns the existing row; provider invoked once; one
  row only.
- GET returns 200 for owner, 404 for non-owner and for missing id; never 403.
- `ai_request_logs` row recorded for success and failure (covered by `callClaude`;
  asserted via correlation id in the repository/service tests).

## Edge cases

- Tone intent in input → variant 1 matches (prompt-enforced; #39 does not re-order, it
  trusts the validated model output).
- Anonymous retry with same key must NOT consume a second rate-limit slot → idempotency
  lookup precedes the usage reservation.
- Concurrent same-key submissions (rare in solo v1): both may call the LLM before either
  inserts; the unique index makes the second insert fail and we re-read. True
  concurrency hardening is out of scope for #39 (belongs to load testing / #60-style work).
- Authenticated GET of an analysis created while anonymous (pre-signup, later signup):
  owner match is by the key present on the row; cross-identity access returns 404 per spec.

## Out of scope

- `POST /expressions` (Save), `POST /tts/playback`, signup/pending-save (other tickets).
- Per-user authenticated analysis limits (unlimited in v1).
- Fixing the `callClaude` token/cost-logging gap (#26).
- Live Anthropic calls or S07 eval runs (#65/#66 own eval).
- The mojibake in `AnonymousAnalysisUsageService`/`ClientIpResolver` Korean messages
  (pre-existing #34 encoding bug) — flag in review, do not fix here.

## Verification commands

- `git status --short --branch`
- `cd backend && ./gradlew test --tests "com.phraselog.analysis.*"`
- `cd backend && ./gradlew test`
- `git diff --check`

If Docker/Testcontainers is unavailable, run unit + MockMvc tests and report that the
JDBC integration check is blocked by the local environment. Do not substitute H2 — it
would not validate `JSONB`/`INET` behavior.

## Risks / notes

- Decision A is the sharp edge: it introduces a migration and touches the idempotency
  contract that `architecture.md` had scoped to Save/Roleplay only. Get explicit approval.
- Decision B relies on the correlation-id lookup; if it proves fragile, fall back to
  threading the id through the logging chain (wider review surface).
- Per CLAUDE.md section 9, this is an AI-pipeline + auth-adjacent change → independent
  review in `docs/reviews/` (spec compliance, then code quality) after implementation.

## Final outcome

Implemented with one migration (V002), both decisions per the recommendations.

- New `com.phraselog.analysis` package: `AnalysisController` (`@RestController`),
  `AnalysisService` (`@Service`), `AnalysisRepository`/`JdbcAnalysisRepository`,
  `UnavailableAnalysisRepository` (no-DB fallback), `AnalysisConfiguration`, and the
  DTOs/records (`AnalysisResponse`, `ExpressionVariantDto`, `CreateAnalysisRequest`,
  `AnalysisRequestRow`, `NewAnalysis`).
- Decision A: `V002__analysis_idempotency_key.sql` adds `idempotency_key` + the two
  per-caller partial unique indexes. Idempotency lookup runs before the rate-limit
  reservation; a lost-response insert race is caught (`DuplicateKeyException`) and re-read.
- Decision B: `ai_request_log_id` resolved via
  `findLogIdByCorrelation(request_correlation_id)` after `callClaude`; nullable/best-effort.
- Decision C: response variant `id` derived as `UUID.nameUUIDFromBytes(analysisId:order)`.

**Wiring change vs the plan.** The plan proposed gating the whole stack with
`@ConditionalOnBean(JdbcTemplate.class)` (mirroring the usage module). During execution
that conflicted with the no-DB scaffold context (`PhraselogBackendApplicationTests`
excludes `DataSourceAutoConfiguration`) and with controller handler detection in tests.
Final wiring instead keeps `AnalysisController`/`AnalysisService` as ordinary
component-scanned beans and:
- gates only `AnalysisRepository` on the DataSource via the `ObjectProvider<DataSource>`
  pattern from `AiLoggingConfiguration` (real Jdbc repo, else `UnavailableAnalysisRepository`);
- injects the DB-conditional `AnonymousAnalysisUsageService` (#34) via
  `ObjectProvider<>` and resolves it lazily at request time, so the `@Service` is
  constructable in the no-DB context (where `/analysis` is never called).

This is more robust than `@ConditionalOnBean` on user config (which has a known
auto-config ordering pitfall, documented in `AiLoggingConfiguration`).

**For the reviewer (out of #39 scope, do not fix here):**
- `callClaude` logs null `input_tokens`/`output_tokens`/cost — the client strips
  `usage` when extracting the content block (pre-existing #26 limitation).
- `AnonymousAnalysisUsageService.rateLimitExceeded()` and `ClientIpResolver.validationFailed()`
  carry mojibake Korean user-messages (pre-existing #34 UTF-8 encoding bug). The anonymous
  `/analysis` 429/400 paths surface these strings.

### Verification run

- `cd backend && .\gradlew.bat test --tests "com.phraselog.analysis.*"`: passed
  (controller + service unit tests; the JDBC Testcontainers tests were **skipped** —
  Docker is unavailable in this environment, `@Testcontainers(disabledWithoutDocker=true)`).
- `cd backend && .\gradlew.bat test`: passed (full suite, incl. the no-DB
  `PhraselogBackendApplicationTests` context load).
- `cd backend && .\gradlew.bat spotlessApply`: applied (googleJavaFormat).
- No live Anthropic/OpenAI call was made; all tests use a mocked `AnthropicService`.

### Follow-ups

- Run the `JdbcAnalysisRepositoryTests` Testcontainers suite in a Docker-enabled
  environment (CI) to validate JSONB/INET/idempotency-unique behavior against real Postgres.
- Codex independent review (spec compliance, then code quality) per CLAUDE.md section 9.

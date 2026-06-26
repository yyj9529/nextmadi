# Exec plan: Roleplay session start/retrieval backend (#59)

Status: **Planned, not implemented.** This plan is grounded in GitHub issue #59,
`docs/api/openapi.yaml`, `docs/screens/s12.md`, `docs/AI_PIPELINE.md`, and the current
backend checkout.

## Goal

Implement issue #59: the authenticated backend contract for starting and restoring a
guided roleplay session.

- `POST /practice/sessions` accepts `expression_id`, optional `coach_id`, and
  `Idempotency-Key`; enforces the daily 2-session limit; initializes the roleplay
  opening with `roleplay_session_init`; records the opening coach turn; and returns
  `201 PracticeSession + opening_turn`.
- `GET /practice/sessions/{id}` returns the owner-only session state with all turns,
  ordered for refresh/return restoration.

## Source specs

- GitHub issue #59: E10.2 Roleplay Session Start/Retrieval Backend.
- `docs/api/openapi.yaml`: `POST /practice/sessions`,
  `GET /practice/sessions/{session_id}`, `PracticeSession`, `PracticeTurn`,
  `Idempotency-Key`, and `RateLimitExceeded`.
- `docs/screens/s12.md`: start behavior, daily limit UX, refresh restoration, opening
  turn, TTS fallback, and the abandoned-session counting decision.
- `docs/AI_PIPELINE.md`: `roleplay_session_init_v1`, routing to Sonnet 4.6 with 30s
  timeout, logging contract, and TTS behavior.
- `docs/data-model.md` and `backend/src/main/resources/db/migration/V001__init_schema.sql`:
  `practice_sessions`, `practice_turns`, `tts_audio_cache`, and `ai_request_logs`.
- `SECURITY.md`: DB migrations and external AI/TTS/S3 calls require owner approval;
  no raw user text in logs.
- `docs/quality-gates.md`: backend/AI gate and independent review for roleplay
  state-machine work.

## Key facts established before coding

- The database already has `practice_sessions`, `practice_turns`, `tts_audio_cache`, and
  seeded `coach_profiles`.
- `openapi.yaml` already declares both #59 endpoints and the response shapes.
- There is no `com.phraselog.practice` package yet.
- `PromptLoader` can load `prompts/roleplay/init/v1.md`; it also supports two-level
  prompt paths such as `roleplay/init`.
- `backend/src/main/resources/schemas/` currently contains only `s07_analysis_v1.json`.
  `roleplay_session_init_v1.json` must be added before the LLM init call can validate.
- `AnthropicService.callClaude(...)` can be reused for `ROLEPLAY_SESSION_INIT`, but it
  currently passes the S07 timeout constant internally. For #59 this is not visible
  because S07 and session init are both 30s, but the method should be corrected to use
  the passed feature while touching this path.
- `UsageRepository.countRoleplaySessions(...)` already counts non-abandoned roleplay
  sessions for a half-open day range. This matches `data-model.md`, but #59 still asks
  the owner to finalize whether abandoned sessions count.
- Issue #30 (`POST /tts/playback` + TTS cache + S3) is still open and there is no TTS
  implementation in the backend. #59 cannot fully return a real `opening_turn.audio_url`
  unless #30 is implemented first or folded into this branch.
- Issue #26 is still open even though some Anthropic client code exists. Do not assume
  all fallback/schema requirements from #26 are complete; keep #59 tests focused on
  the session-init behavior needed here.

## Blocking decisions before implementation

### Decision A - abandoned sessions and daily limit

**Recommended default:** abandoned sessions do not count toward the 2-session daily cap.

Reason: `docs/data-model.md` and `JdbcUsageRepository` already use `status <> 'abandoned'`,
and S11 usage display is meant to match the same query. If the owner agrees, update
`docs/screens/s12.md` to replace the TBD with this rule. If the owner chooses the other
behavior, update both `UsageRepository` expectations and the #59 service limit query so
S11 display and server enforcement do not diverge.

### Decision B - idempotency storage

`POST /practice/sessions` cannot be reliably idempotent with the current schema because
`practice_sessions` has no `idempotency_key`.

**Recommended:** add a Flyway migration with `practice_sessions.idempotency_key UUID` and
a partial unique index on `(user_id, idempotency_key)` where the key is not null. Look up
by `(user_id, key)` before the daily-limit check and before provider calls.

This is a DB migration, so it requires owner approval before coding.

### Decision C - TTS dependency

**Recommended:** close #30 first, then implement #59 against the shared TTS service.

If the owner wants #59 in one branch now, explicitly treat this as scope expansion:
implement the minimal #30 service boundary needed by #59 (`text + voice_id -> cache row +
signed audio URL`) and make the PR description say it includes the TTS prerequisite.
Returning a fake URL or silently nulling `audio_url` on the success path would not satisfy
the issue contract.

## Design

### POST /practice/sessions flow

1. Read the verified `InternalAuthPrincipal` from the request attribute and require an
   authenticated `user_id` principal. A session-token principal is not enough for S12.
2. Validate request body and headers manually:
   - `expression_id` is required and must be a UUID.
   - `coach_id`, when present, must be a UUID.
   - `Idempotency-Key` is required and must be a UUID.
3. Idempotency lookup by `(user_id, idempotency_key)`. If found, return the existing
   session and opening turn without re-checking the limit or re-calling LLM/TTS.
4. Resolve the source expression by `expression_id` and `user_id`; missing/not-owned or
   soft-deleted returns 404.
5. Resolve coach:
   - Use request `coach_id` when present.
   - Otherwise use `users.selected_coach_id`.
   - If neither exists, return 400 `validation_failed`.
   - If the coach id is unknown, return 400 `validation_failed`.
6. Enforce the daily cap before provider calls. Count sessions in `[todayStart,
   tomorrowStart)` using the same abandoned-session rule finalized in Decision A.
   If count is already 2, return 429 with issue #59's user message and create no row.
7. Build the session-init prompt input from:
   - the roleplay init prompt body;
   - the selected coach persona context from `coach_profiles.prompt_template_ref`;
   - the saved expression's original situation and selected English variant.
8. Generate a new `request_correlation_id`, call
   `AnthropicService.callClaude(AiFeature.ROLEPLAY_SESSION_INIT, ...)`, and validate
   `roleplay_session_init_v1`.
9. Validate `planned_turns` is 3-10 defensively in service code even though the schema
   should enforce it.
10. Synthesize the opening line through the TTS service from #30 using the chosen coach's
    `tts_voice_id`. On success, keep `tts_audio_cache_id` and signed `audio_url`.
    On TTS failure, follow `AI_PIPELINE.md`/S12 fallback: continue text-only only if the
    shared TTS service has logged the failure; return `tts_audio_url = null`.
11. Persist the session and opening coach turn atomically:
    - `practice_sessions`: `user_id`, `expression_id`, `coach_id`, `status='active'`,
      `planned_turns`, `started_at`, `idempotency_key`.
    - `practice_turns`: `turn_number=1`, `speaker='coach'`, `text_content=scenario_setup`,
      optional `tts_audio_cache_id`.
12. Return `201` with the session fields and `opening_turn`.

Note: `practice_sessions.planned_turns` is `NOT NULL`, so the implementation should call
session init before inserting the session row, then persist the valid session and opening
turn together. This slightly changes the issue's textual order, but preserves the visible
contract and avoids placeholder sessions.

### GET /practice/sessions/{id} flow

1. Require authenticated `user_id`.
2. Parse `session_id`; malformed id returns 404.
3. Load session by `id` and `user_id`; missing/not-owned returns 404, not 403.
4. Load all turns ordered by `turn_number`.
5. For turns with `tts_audio_cache_id`, return a signed URL through the same TTS/S3 helper
   used by #30. Do not re-call OpenAI TTS on GET.
6. Return the `PracticeSession` shape with `turns`.

## Files expected to change

Docs:
- `docs/exec-plans/2026-06-26-roleplay-session-start-retrieval.md`
- `docs/screens/s12.md` to resolve the abandoned-session daily-limit TBD.
- `docs/data-model.md` if `practice_sessions.idempotency_key` is approved.

Migration, if Decision B is approved:
- `backend/src/main/resources/db/migration/V004__practice_session_idempotency_key.sql`

AI schema:
- `backend/src/main/resources/schemas/roleplay_session_init_v1.json`
- `backend/src/test/java/com/phraselog/ai/client/service/JsonSchemaValidatorTests.java`
  or a focused schema test for `roleplay_session_init_v1`.
- `backend/src/main/java/com/phraselog/ai/client/service/AnthropicService.java`
  to pass the actual feature into `FeatureRouting.getTimeoutForFeature(feature)`.

Practice package:
- `backend/src/main/java/com/phraselog/practice/config/PracticeConfiguration.java`
- `backend/src/main/java/com/phraselog/practice/controller/PracticeSessionController.java`
- `backend/src/main/java/com/phraselog/practice/service/PracticeSessionService.java`
- `backend/src/main/java/com/phraselog/practice/repository/PracticeSessionRepository.java`
- `backend/src/main/java/com/phraselog/practice/repository/JdbcPracticeSessionRepository.java`
- `backend/src/main/java/com/phraselog/practice/repository/UnavailablePracticeSessionRepository.java`
- DTO/record files for start request, session response, turn response, internal rows, and
  insert commands.

Existing repository extensions:
- `backend/src/main/java/com/phraselog/expression/repository/ExpressionRepository.java`
  and `JdbcExpressionRepository.java` only if the current `ExpressionResponse` lacks enough
  selected-variant detail for prompt construction.
- `backend/src/main/java/com/phraselog/coach/repository/CoachRepository.java` and
  `JdbcCoachRepository.java` to expose internal coach details such as
  `prompt_template_ref` and `tts_voice_id`.
- `backend/src/main/java/com/phraselog/user/repository/UserRepository.java` only if the
  practice service needs a narrower selected-coach lookup than `findById`.

TTS dependency, if #30 is folded in:
- `backend/src/main/java/com/phraselog/tts/**` service/repository/client files and tests.
  Otherwise #59 should depend on those files after #30 lands.

Tests:
- `backend/src/test/java/com/phraselog/practice/controller/PracticeSessionControllerTests.java`
- `backend/src/test/java/com/phraselog/practice/service/PracticeSessionServiceTests.java`
- `backend/src/test/java/com/phraselog/practice/repository/JdbcPracticeSessionRepositoryTests.java`
- Existing daily usage tests if the abandoned-session rule changes.

## Implementation sequence

1. Confirm Decisions A, B, and C with the owner.
2. If approved, add the `practice_sessions.idempotency_key` migration and docs update.
3. Add `roleplay_session_init_v1.json` and schema validation tests.
4. Correct `AnthropicService` timeout routing to use the actual `feature`.
5. Add internal repository read models:
   - expression plus selected variant for prompt input;
   - coach profile plus `prompt_template_ref` and `tts_voice_id`;
   - user selected coach lookup if needed.
6. Implement `PracticeSessionRepository` with:
   - find existing session by `(user_id, idempotency_key)`;
   - count today's sessions or reuse `UsageRepository`;
   - insert session plus opening turn atomically;
   - load session with turns for GET.
7. Implement `PracticeSessionService` orchestration.
8. Implement `PracticeSessionController` with MockMvc coverage and existing error contract
   patterns.
9. Wire DB/no-DB behavior through `PracticeConfiguration`, mirroring the repository fallback
   pattern from `AnalysisConfiguration` and `ExpressionConfiguration`.
10. Run focused tests first, then backend quality gates.

## Acceptance criteria

- `POST /practice/sessions` returns 201 for an authenticated user with a saved expression
  and valid `Idempotency-Key`.
- The chosen coach is confirmed at start time: explicit `coach_id` wins; otherwise
  `users.selected_coach_id` is used.
- The service calls `roleplay_session_init`, persists `planned_turns` in the 3-10 range,
  records the opening coach turn as `turn_number=1`, and returns it.
- The init call is logged to `ai_request_logs` with `feature_name='roleplay_session_init'`
  and the generated `request_correlation_id`.
- A successful TTS path returns `opening_turn.tts_audio_url`.
- TTS failure follows S12 fallback and does not erase the text opening turn.
- The 3rd non-abandoned session attempt in the day returns 429 and creates no
  `practice_sessions` row.
- Retrying the same `Idempotency-Key` returns the existing session without another LLM/TTS
  call and without consuming another daily slot.
- `GET /practice/sessions/{id}` returns the session plus full ordered `turns` array for
  the owner.
- Missing, malformed, or non-owned session ids return 404.

## Test plan

Unit/service tests:
- authenticated principal required; session-token principal rejected;
- missing/invalid `Idempotency-Key` rejected before provider calls;
- expression missing/not-owned returns 404;
- default coach resolution uses `selected_coach_id`;
- explicit coach id overrides selected coach;
- no selected coach and no explicit coach returns 400;
- daily limit 3rd attempt returns 429 before LLM/TTS and inserts no row;
- idempotent retry returns existing session and does not call LLM/TTS again;
- `planned_turns` outside 3-10 from a mocked provider is rejected defensively;
- TTS success links cache id and returns URL;
- TTS failure returns text-only opening turn if #30's error/logging contract supports it.

Repository integration tests with Testcontainers:
- migration adds `idempotency_key` and unique index;
- insert session + opening turn is atomic;
- duplicate `(user_id, idempotency_key)` cannot create a second session;
- daily count follows the finalized abandoned-session rule;
- GET loads turns ordered by `turn_number` and excludes other users.

Controller tests:
- POST passes principal, body, and idempotency key into the service and returns 201;
- GET passes `session_id` into the service and returns session with turns;
- missing verified principal returns the existing 401 error contract.

Verification commands:
- `git status --short --branch`
- `cd backend && .\gradlew.bat test --tests "com.phraselog.practice.*"`
- `cd backend && .\gradlew.bat test --tests "com.phraselog.ai.client.service.*"`
- `cd backend && .\gradlew.bat cleanTest test --rerun-tasks`
- `cd backend && .\gradlew.bat spotlessJavaCheck`
- `git diff --check`

If Docker is unavailable, report Testcontainers coverage as not verified locally and rely
on CI or a Docker-enabled run for the JDBC migration/repository checks.

## Risks and review notes

- This is a roleplay state-machine and AI pipeline entry point. Per `CLAUDE.md` section 9,
  it needs an independent review in `docs/reviews/` after implementation.
- DB migration approval is required before adding `practice_sessions.idempotency_key`.
- #30 is a real dependency for `audio_url`. Do not claim #59 is done if TTS/S3 is still
  stubbed or absent.
- Do not make live Anthropic/OpenAI/S3 calls in normal tests. Mock providers; run live
  calls only with explicit owner approval.
- Do not store raw user text in `ai_request_logs`. The prompt input can include expression
  text for the provider call, but logs must stay metadata-only.
- GET restoration must never re-run LLM or TTS synthesis. It may only sign existing cached
  audio keys.

## Final outcome

Not executed yet.

## What changed after execution

Not executed yet.

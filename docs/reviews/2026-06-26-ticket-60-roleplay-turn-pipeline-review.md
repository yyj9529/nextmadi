# Review Readiness - Roleplay Turn Pipeline (#60)

- **Date:** 2026-06-26
- **Scope:** `feat/60-roleplay-turn-pipeline`
- **Target:** GitHub issue #60, `POST /practice/sessions/{session_id}/turns`
- **Review status:** implementation self-check; independent review is still required for
  this roleplay state-machine change.
- **Verdict:** **PASS for the backend turn core, with dependency caveats.** The turn
  endpoint, idempotency storage, roleplay response/feedback schemas, correlation-id
  reuse, timeout routing fix, OpenAPI contract, and focused tests are in place.
  Do not close #60 end-to-end until session start/retrieval (#59) and backend TTS
  audio URL generation (#30) are wired.

## Gate 1 - Spec Compliance

| Criterion | Result | Evidence |
|---|---|---|
| Authenticated owner and active-session gate | PASS | `PracticeTurnService` resolves `user_id`, rejects session-token principals, looks up sessions by `(session_id, user_id)`, and rejects inactive sessions. |
| Required idempotency key | PASS | `Idempotency-Key` is parsed as a UUID before processing; `practice_turn_requests` has `UNIQUE(session_id, idempotency_key)`. |
| JSON and multipart turn input | PASS | Controller tests cover JSON `text_content`, multipart audio, and multipart text fallback. |
| Low-confidence/no-input does not consume a turn | PASS | Service stores `status='no_turn'`, returns `turn_consumed=false` with `retry_prompt`, and does not call LLM/TTS. |
| Consumed user-turn count | PASS | Repository counts persisted rows where `speaker='user'`; no-turn requests do not add rows. |
| Turn response, feedback, and TTS failure policy | PASS | Turn-response failure marks request failed and appends no turns; feedback failure continues with `feedback=null`; TTS failure continues with text-only coach turn. |
| Completion policy | PASS | Repository marks `practice_sessions.status='completed'` and `ended_at=now()` when consumed user turns reach `planned_turns`. |
| Cost/correlation grouping | PASS | One `request_correlation_id` is reserved per turn and reused for STT, LLM, feedback, and TTS boundaries. |
| #59 session start/retrieval dependency | BLOCKED OUTSIDE THIS CHANGE | The turn endpoint requires an existing active `practice_sessions` row and opening coach turn. `POST /practice/sessions` / `GET /practice/sessions/{id}` are not implemented here. |
| #30 backend TTS dependency | PARTIAL | `PracticeAudioService` is present, but the default implementation is no-op until the backend TTS package is implemented. |

## Gate 2 - Code Quality

| Criterion | Result | Evidence |
|---|---|---|
| Schema contract | PASS | Added `roleplay_turn_response_v1.json` and `roleplay_turn_feedback_v1.json`; schema tests cover valid and invalid feedback shapes. |
| DB migration | PASS WITH DOCKER CAVEAT | SQL structure test covers fields, status check, uniqueness, and indexes. Testcontainers repository tests were skipped because Docker was unavailable in this run. |
| Error contract | PASS | New service paths throw `ApiErrorException` with existing error response handling. |
| No raw user text persistence in idempotency table | PASS | `practice_turn_requests` stores ids, status, retry prompt, and turn references only. |
| No live provider calls in tests | PASS | STT, LLM, feedback, and TTS boundaries are mocked in normal tests. |

## Verification Run

- `.\gradlew.bat test --tests "com.phraselog.practice.*" --tests "com.phraselog.ai.client.service.AnthropicServiceTests.callClaudeUsesFeatureSpecificTimeoutForRoleplayTurnResponse" --tests "com.phraselog.ai.client.service.JsonSchemaValidatorTests.acceptsValidRoleplayTurnResponse" --tests "com.phraselog.ai.client.service.JsonSchemaValidatorTests.acceptsRoleplayFeedbackFalseWithoutExtraFields" --tests "com.phraselog.ai.client.service.JsonSchemaValidatorTests.rejectsRoleplayFeedbackTrueWithoutKoreanComment" --tests "com.phraselog.transcription.service.TranscriptionServiceTests.transcribeCanUseCallerSuppliedCorrelationIdForInlineRoleplayTurns" --tests "com.phraselog.db.MigrationSqlStructureTests.practiceTurnRequestsMigrationContainsIdempotencyAndReplayFields"`: `BUILD SUCCESSFUL`.
- `.\gradlew.bat cleanTest test --rerun-tasks`: `BUILD SUCCESSFUL`; 265 tests, 0 failures, 0 errors, 60 skipped.
- `.\gradlew.bat spotlessJavaCheck`: `BUILD SUCCESSFUL`.

## Close Recommendation

Keep #60 open or mark the PR as stacked/partial until #59 and #30 are present.
Before merge, run the repository/Testcontainers tests with Docker available and have
an independent reviewer check the roleplay state machine against `docs/screens/s12.md`
and `docs/AI_PIPELINE.md`.

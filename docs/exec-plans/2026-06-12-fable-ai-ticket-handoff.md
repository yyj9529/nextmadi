# Fable AI Ticket Handoff

Date: 2026-06-12
Status: Draft handoff for implementation
Issues: #26, #39, #60

## Summary

This handoff captures the Fable-priority design work that can be completed before
the Spring Boot backend scaffold exists. It does not close the implementation
tickets. It gives the future backend implementer the exact behavior, test matrix,
and dependency boundaries for the AI-heavy tickets.

Current local constraint: the repo has a Next.js scaffold and AI/eval docs, but no
Spring Boot source tree yet. Therefore #26, #39, and #60 should not be marked done
until the backend implementation and tests exist.

## #26 Anthropic Client - Routing, Schema Validation, Fallback

Goal: one backend client path for all Claude LLM calls, keyed by `feature_name`.

Dependencies:
- #14 DB schema for `ai_request_logs` FK consumers.
- #27 logging module for success/failure/cost rows.
- #28 prompt loader for front-matter and prompt body loading.
- #77 error response contract for surfaced failures.

Feature routing:

| Feature | Model | Timeout | Prompt | Schema |
| --- | --- | --- | --- | --- |
| `s07_analysis` | `claude-sonnet-4-6` | 30s | `prompts/s07/v{N}.md` | `s07_analysis_v1` |
| `roleplay_session_init` | `claude-sonnet-4-6` | 30s | `prompts/roleplay/init/v{N}.md` | `roleplay_session_init_v1` |
| `roleplay_turn_response` | `claude-sonnet-4-6` | 15s | `prompts/roleplay/turn/v{N}.md` | `roleplay_turn_response_v1` |
| `roleplay_turn_feedback` | `claude-haiku-4-5` | 10s | `prompts/roleplay/feedback/v{N}.md` | `roleplay_turn_feedback_v1` |
| `roleplay_result` | `claude-sonnet-4-6` | 30s | `prompts/roleplay/result/v{N}.md` | `roleplay_result_v1` |

Schema rules:
- Reject non-JSON text before schema validation.
- Require `s07_analysis.expressions.length == 3`.
- Require `roleplay_session_init.planned_turns` from 3 to 10.
- Require `roleplay_turn_response.coach_utterance` non-empty.
- For `roleplay_turn_feedback`, allow exactly `{"show_feedback": false}` when false; require `natural_alternative` and `korean_comment` when true.
- For `roleplay_result`, allow empty `recommended_expressions`, `awkward_pairs`, and `pronunciation_focus_words`; require `coach_encouragement`.

Fallback matrix:

| Failure | Retry | Final error_code | User action |
| --- | --- | --- | --- |
| Invalid JSON or schema mismatch | once with constraint reminder | `schema_validation_failed` | retry CTA where screen allows |
| Provider 5xx | once after 500ms | `provider_5xx` | retry CTA |
| Provider 429 | retries at 1s and 3s | `provider_429` | retry later CTA |
| Timeout | no retry | `timeout` | retry CTA |
| Network error | once after 500ms | `network` | retry CTA |

Implementation tests:
- Unit: each feature resolves the expected model, prompt path, timeout, and schema.
- Unit: schema validation rejects missing required fields for all 5 schemas.
- Unit: schema validation retry appends the constraint reminder exactly once.
- Unit: provider 5xx retries once and then returns `provider_5xx`.
- Unit: provider 429 retries twice with 1s/3s schedule using a fake clock.
- Unit: timeout does not retry.
- Integration: every terminal status calls #27 logging exactly once with the same
  `request_correlation_id` passed into the client.

## #39 S07 Analysis Backend

Goal: `POST /analysis` and `GET /analysis/{id}` for the core S07 analysis flow.

Dependencies:
- #14 DB schema: `analysis_requests`, `anonymous_analysis_usage`, `ai_request_logs`.
- #20 BFF internal token claims for authenticated `user_id` or anonymous `session_token`.
- #26 Anthropic client.
- #27 logging.
- #28 prompt loader.
- #34 anonymous daily limit.
- #77 error response contract.

POST `/analysis` behavior:
- Validate `input_text` is present and <= 500 chars.
- Require `Idempotency-Key`.
- Authenticated users are unlimited in v1.
- Anonymous users count against #34 2-per-day/IP limit before the LLM call.
- Call `s07_analysis` with prompt version from `prompts/s07/v1.md`.
- Validate exactly 3 expressions.
- Persist `analysis_requests.output_json`, `prompt_version`, and `ai_request_log_id`.
- Return 201 with `AnalysisRequest` response shape from `openapi.yaml`.

GET `/analysis/{id}` behavior:
- Authenticated: authorize by `user_id`.
- Anonymous: authorize by `session_token`.
- Return 404 for missing or not-owned analysis, never 403.

Idempotency:
- Same `Idempotency-Key` and same caller should return the existing successful row.
- If the first attempt failed before DB insert, retry may create the row.
- If the first attempt created a row but the response was lost, retry must not call
  the LLM again.

Implementation tests:
- 400 when `input_text` is blank or > 500 chars.
- Anonymous third daily attempt returns 429 before LLM call.
- Authenticated request bypasses anonymous usage.
- Successful request persists prompt version and output JSON.
- Invalid LLM schema maps through #26 and #77.
- Retry with same idempotency key does not double-bill or double-insert.
- GET returns 200 for owner and 404 for not-owner.
- Success and failure both create or update the expected #27 log rows without raw
  user text.

## #60 S12 Turn Pipeline State Machine

Goal: one user turn in an active roleplay session runs STT, coach response,
feedback, TTS, persistence, and completion checks without corrupting turn order.

Dependencies:
- #14 DB schema: `practice_sessions`, `practice_turns`, `tts_audio_cache`, `ai_request_logs`.
- #26 Anthropic client.
- #27 logging.
- #29 STT endpoint/client.
- #30 TTS playback/cache.
- #58 roleplay prompts.
- #59 session start/retrieval backend.
- #77 error response contract.

Normal text turn:
1. Verify session belongs to user and is `active`.
2. Resolve `text_content` from JSON body.
3. Generate one `request_correlation_id` for the whole turn.
4. Insert user `practice_turns` row at next user turn number.
5. Call `roleplay_turn_response`.
6. Call `roleplay_turn_feedback`.
7. Call TTS for coach utterance.
8. Insert coach `practice_turns` row.
9. If the user turn reaches `planned_turns`, set session `completed` and `ended_at`.
10. Return `user_turn`, `coach_turn`, nullable `feedback`, and `session_status`.

Normal voice turn differences:
- Accept multipart audio.
- Run STT first.
- If transcript is empty or below confidence threshold, do not insert a user turn,
  do not call LLM/TTS, and return retry prompt with `session_status = active`.

Failure behavior:
- STT low confidence: no turn consumed.
- `roleplay_turn_response` failure: do not insert coach row; return structured turn error.
- `roleplay_turn_feedback` failure or timeout: continue with `feedback = null`.
- TTS failure: insert coach row with text and null audio; continue text-only.
- Network drop/retry: `Idempotency-Key` returns the existing result for the completed
  turn, including coach turn and feedback status.

Turn numbering invariant:
- A user utterance and coach response are persisted as separate rows.
- The opening coach utterance from #59 is turn 1.
- Each consumed user utterance increments the sequence, followed by one coach row.
- Low-confidence STT creates no row and does not advance the sequence.
- Enforce uniqueness with `(session_id, turn_number)`.

Implementation tests:
- Normal text turn stores user and coach rows, returns active status when not final.
- Normal voice turn stores derived transcript and STT confidence.
- Low-confidence voice result consumes no turn and makes no LLM/TTS calls.
- Haiku failure skips feedback but still stores coach response.
- TTS failure returns text-only coach turn.
- Final planned turn marks session completed.
- Duplicate `Idempotency-Key` returns same turn result without duplicate rows.
- Concurrent duplicate submissions cannot create duplicate turn numbers.
- All STT/LLM/TTS calls for one turn share one `request_correlation_id`.

## Roll-forward Recommendation

Close order after this handoff:
1. Finish #14, #27, and #28 so the backend can persist logs and load prompts.
2. Implement #26 with the schema and fallback matrix above.
3. Implement #39 for S07 and connect it to #65 eval CI.
4. Implement #59 before #60.
5. Implement #60 using the state-machine tests above before #62 result generation.

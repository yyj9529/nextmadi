# AI Pipeline

Source of truth for the AI pipeline that powers PhraseLog v1. Whisper STT → Claude LLM → OpenAI TTS, split (ADR-001). This document defines per-stage behavior, JSON schemas, routing logic, logging contract, and timeout/fallback policy.

Stack overview lives in `architecture.md`. Database structure for AI-related tables lives in `data-model.md`. Decision rationale for the split pipeline lives in ADR-001.

## Pipeline overview

```
User voice ──► Whisper STT ──► transcript ─┐
                                            │
User text ──────────────────────────────────┤
                                            ▼
                              ┌──── feature router ────┐
                              │   feature_name based   │
                              └────────────┬───────────┘
                                           │
                          ┌────────────────┼────────────────┐
                          ▼                ▼                ▼
                   Sonnet 4.6        Haiku 4.5         (text-only path)
                   (deep tasks)      (quick tasks)
                                           │
                                           ▼
                              structured JSON output
                                           │
                                           ▼
                              OpenAI TTS ──► audio_url (S3)
                                           │
                                           ▼
                                       client
```

Every external API call writes a row to `ai_request_logs` (see Logging Contract section).

## Stages

### Stage 1 — STT (Whisper)

Provider: OpenAI Whisper.

Used by: S02 try-without-login voice input and S04 home mic via the standalone
`POST /transcriptions` endpoint (two-step flow: transcribe -> user
confirms/edits transcript -> text-only `POST /analysis`), and S12 roleplay user
turns inline within `POST /practice/sessions/{id}/turns` (one-shot;
conversation flow does not pause for transcript editing). Every STT call,
standalone or inline, logs one `ai_request_logs` row with purpose
`stt_transcription`.

Input: audio file (WebM/Opus from browser MediaRecorder), max 60 seconds (S12 turn cap).

Output: plain text transcript (required). Per-segment confidence is NOT a guaranteed field: whisper-1 (verbose_json) exposes segment `avg_logprob` as a rough proxy (via `exp(avg_logprob)`); token-level logprobs are available only on `gpt-4o-transcribe` / `gpt-4o-mini-transcribe` with `response_format=json`.

`practice_turns.stt_confidence` is NULLABLE. When the selected transcription model exposes a confidence/logprob signal, record the lowest-confidence segment (for `whisper-1`, derive it from `avg_logprob`); otherwise leave it null. The 0.5 retry threshold is a placeholder to tune after the transcription model is confirmed (see Open Questions) and against real user audio — TBD in `docs/screens/s12.md`.

Timeout: 30 seconds (matches S07 user-facing timeout in PPT v2.2 slide 8). On timeout, the call returns a structured error and the user sees a retry CTA.

Cost: $0.006 per minute of audio (verified May 23, 2026; OpenAI public pricing for `whisper-1`). 30-second utterance ≈ $0.003. See Cost and latency section below for the consolidated pricing table.

### Stage 2 — LLM (Claude)

Provider: Anthropic (Sonnet 4.6 for deep tasks, Haiku 4.5 for quick per-turn feedback).

Model selection is per `feature_name`, not per request. The routing table is in the next section.

All LLM calls:

1. Use a versioned prompt file under `prompts/{feature}/v{N}.md`.
2. Return a JSON-only response (no prose preamble).
3. Are validated against the JSON schema in this document before the response is used.
4. Log to `ai_request_logs` regardless of success or failure.

Schema validation failures retry once with a constraint reminder appended to the prompt ("Your previous response did not match the required JSON schema. Return only the JSON object."). Second failure returns a structured error to the application. The application surfaces an end-user message and logs the failure.

Timeout: 60 seconds for `s07_analysis`. 30 seconds for `roleplay_session_init`, `roleplay_result`. 15 seconds for `roleplay_turn_response`. 10 seconds for `roleplay_turn_feedback` (must not block the next turn).

`s07_analysis` was 30 seconds until 2026-07-25. Two things were true at once: the value came from a planning storyboard rather than measurement, and the client accepted the timeout argument without ever applying it to the HTTP call — so nothing enforced any limit. Once enforcement was fixed, three real keyed calls measured 25.7s / 27.8s / 35.1s (`ai_request_logs.latency_ms`, 2026-07-25, prompt `s07-v1`, `claude-sonnet-4-6`), a mean sitting on top of the old limit. 60 seconds is set from that measurement. Sample size is three; re-check against accumulated `latency_ms` once real traffic exists.

The roleplay values remain unmeasured v1 working defaults. Their outputs are at least as large as S07's, so they are likely to need the same correction — measure before changing them, rather than generalizing from S07.

A 30-second wait is a UX problem independent of the timeout value; reducing latency (model routing, streaming) is tracked separately and is not solved by widening the budget.

### Stage 3 — TTS (OpenAI TTS)

Provider: OpenAI TTS.

Voice per coach: `coach_profiles.tts_voice_id`. v1 ships three voice IDs (one per Mia/David/Sarah).

Used by: S07 expression playback (on user tap), S12 coach utterances (auto-play on receipt).

Output: MP3 audio file, written to S3, served via signed URL with 12-hour expiry (bounded by the EC2 instance role's temporary credential lifetime; the client refetches via `POST /tts/playback` on expiry — a cache hit, no extra cost). See `architecture.md` "S3 (audio cache)".

Caching: S3 keys are computed as `tts/{voice_id}/{sha256(text)}.mp3`. Identical (voice, text) pairs reuse the cached audio without a new TTS call. Cache hit logs to `ai_request_logs` with `feature_name = "tts_synthesis"`, `status = "cache_hit"`, and `latency_ms < 50`.

Timeout: 15 seconds. On timeout, the calling screen falls back to text-only display (S12) or shows a "음성 재생 실패, 다시 시도해주세요" CTA (S07).

Cost: $15 per 1M characters for TTS-1 standard (verified May 23, 2026). An 80-character coach utterance ≈ $0.0012. HD voice option ($30/1M chars) is not used in v1. See Cost and latency section below.

## Feature routing

The `feature_name` enum from `data-model.md` determines which model is called and with what prompt.

| `feature_name`             | Model       | Prompt template ref        | Timeout | Output JSON schema |
|----------------------------|-------------|----------------------------|---------|--------------------|
| `s07_analysis`             | Sonnet 4.6  | `prompts/s07/v{N}.md`      | 60s     | `s07_analysis_v1`  |
| `roleplay_session_init`    | Sonnet 4.6  | `prompts/roleplay/init/v{N}.md` | 30s | `roleplay_session_init_v1` |
| `roleplay_turn_response`   | Sonnet 4.6  | `prompts/roleplay/turn/v{N}.md` | 15s | `roleplay_turn_response_v1` |
| `roleplay_turn_feedback`   | Haiku 4.5   | `prompts/roleplay/feedback/v{N}.md` | 10s | `roleplay_turn_feedback_v1` |
| `roleplay_result`          | Sonnet 4.6  | `prompts/roleplay/result/v{N}.md` | 30s | `roleplay_result_v1` |
| `stt_transcription`        | Whisper     | (no prompt)                | 30s     | (Whisper response) |
| `tts_synthesis`            | OpenAI TTS  | (no prompt)                | 15s     | (audio bytes)      |

`{N}` is the current prompt version. The prompt file referenced at runtime is determined by `coach_profiles.prompt_template_ref` for coach-specific prompts, and by a config constant for `s07_analysis`.

Why Sonnet for roleplay turn response but Haiku for turn feedback: the coach utterance must stay in character and remember the session arc, which Sonnet handles reliably. Per-turn feedback is a small, structured judgment call ("is this utterance natural or awkward, in one line of Korean?") — Haiku is fast enough to not delay the next turn and costs 1/3 as much (Haiku 4.5 $1/$5 per MTok vs Sonnet 4.6 $3/$15 per MTok; see Cost and latency section).

## JSON output schemas

All schemas are versioned. v1 schemas below are starting points for W4 development. Field names may adjust during prompt iteration in W4-8; schema version bumps will be logged via `ai_request_logs.prompt_version`.

### `s07_analysis_v1`

Returned by Sonnet 4.6 in response to a user-submitted Korean situation. Stored in `analysis_requests.output_json`.

```json
{
  "expressions": [
    {
      "english": "string (the English expression)",
      "tone_label": "string (Korean phrase, dynamically generated, e.g. '정중한', '단호한')",
      "ipa": "string (IPA transcription)",
      "korean_pronunciation": "string (Korean phonetic guide, e.g. '익스큐즈 미')",
      "pronunciation_tip": "string (one short Korean sentence pointing to a difficult sound)",
      "cultural_tip": "string (one short Korean sentence on when/with whom to use this)"
    }
  ]
}
```

The `expressions` array contains exactly 3 entries. If the user's input contains a tone intent (e.g. "정중하게 말하고 싶었어요"), the matching tone is placed first.

### `roleplay_session_init_v1`

Returned by Sonnet 4.6 on roleplay session start (the first call). Stored in `practice_sessions` via `planned_turns` plus `practice_turns` row for the coach's opening utterance.

```json
{
  "planned_turns": 5,
  "scenario_setup": "string (the coach's opening utterance, English, to be sent to TTS)",
  "complexity_rationale": "string (one Korean sentence explaining the planned turn count; logged, not user-facing)"
}
```

`planned_turns` must be an integer between 3 and 10.

### `roleplay_turn_response_v1`

Returned by Sonnet 4.6 for each coach turn after the user speaks. Saved as the coach-speaker row in `practice_turns`.

```json
{
  "coach_utterance": "string (English, to be sent to TTS)"
}
```

The model receives the full session transcript so far. The application enforces session end at `turn_number == planned_turns`.

### `roleplay_turn_feedback_v1`

Returned by Haiku 4.5 after each user utterance, evaluating whether to surface in-turn feedback. Stored in `practice_turns.feedback_content` for the user-speaker row.

```json
{
  "show_feedback": true,
  "natural_alternative": "string (English, only present when show_feedback=true)",
  "korean_comment": "string (one short line, only present when show_feedback=true)"
}
```

When `show_feedback` is false, the response contains only `{"show_feedback": false}` and no card is rendered. The application also writes `practice_turns.feedback_shown = false` in this case.

Trigger criteria (defined in the prompt, not enforced by schema): grammar error, unnatural collocation, or direct-translation vocabulary. Natural utterances proceed without a feedback card.

### `roleplay_result_v1`

Returned by Sonnet 4.6 once when the session reaches its final turn. Stored in `practice_sessions.result_json`.

```json
{
  "recommended_expressions": [
    {
      "english": "string",
      "tone_label": "string",
      "ipa": "string",
      "korean_pronunciation": "string",
      "pronunciation_tip": "string",
      "cultural_tip": "string"
    }
  ],
  "awkward_pairs": [
    {
      "user_said": "string (English, what the user said)",
      "natural_version": "string (English, the smoother alternative)",
      "comment": "string (one Korean line)"
    }
  ],
  "pronunciation_focus_words": [
    "string (English word the user struggled with, inferred from STT confidence and known Korean-speaker error patterns)"
  ],
  "coach_encouragement": "string (Korean, one short paragraph)"
}
```

`recommended_expressions` has exactly 3 entries. The user can save any of them via S12b → S08; the request sends a 0-based `recommended_expression_index`, and saving creates an `expressions` row with `source_type = 'roleplay_result'`.

`awkward_pairs` has 0-3 entries.

`pronunciation_focus_words` may be empty; the UI shows "발음 깔끔했어요" in that case.

## Logging contract

Every **attempt** at an external API call (Whisper, Sonnet, Haiku, OpenAI TTS) writes one row to `ai_request_logs`, regardless of success or failure. A logical call that the fallback policy below retries therefore writes one row per attempt — two rows for a single retry, up to three for the 429 schedule. Success and failure paths are equally important — error rates are the regression signal for model and prompt changes.

The per-attempt grain is deliberate: a retried attempt consumes billed tokens, and a logging contract that folds retries into a single row cannot account for them (ADR-011). Retries exist only on the Claude path; STT and TTS never retry, so those calls always produce exactly one row.

Required fields on every row:

- `feature_name` — from the enum above
- `model_name` — exact model identifier (e.g., `claude-sonnet-4-6`, `whisper-1`)
- `latency_ms` — measured from request send to response receive **for that attempt**, excluding inter-attempt backoff
- `status` — `success` | `error` | `timeout` | `cache_hit` (TTS only)
- `created_at` — write time
- `request_correlation_id` — UUID generated at the start of a user-initiated action; all downstream calls share it
- `attempt_group_id` — UUID generated per logical call; all attempts of that call share it
- `attempt_number` — 1-based; `1` on a first attempt, `2`/`3` on retries
- `is_final_attempt` — true on exactly one row per group: the attempt whose outcome the caller saw

For LLM calls additionally:

- `prompt_version` — from the prompt file front-matter
- `input_tokens`, `output_tokens` — from that attempt's API response
- `estimated_cost_usd` — computed at log time using current public pricing

For TTS additionally:

- `estimated_cost_usd` — character count × pricing per 1K chars

For failures additionally:

- `error_code` — one of `schema_validation_failed`, `provider_5xx`, `provider_429`, `timeout`, `network`, `unknown`. Each attempt records **its own** failure code, so a mixed sequence (5xx, then a schema failure on the retry) preserves both.

### Cost and token semantics

`estimated_cost_usd`, `input_tokens`, and `output_tokens` record the cost of **that one attempt**, never a roll-up. The cost of a logical call is `SUM(estimated_cost_usd)` grouped by `attempt_group_id`; total spend is `SUM(estimated_cost_usd)` with no attempt filter at all. A `NULL` means the cost is unknown (no usage returned), not zero — sums are therefore a lower bound. Only `cache_hit` rows carry a known `0`.

Adding a `WHERE is_final_attempt` filter to a cost query is a bug: it silently drops the tokens burned by failed attempts, which is the exact defect this contract replaces.

`prompt_version` is the version of the loaded prompt file. The constraint reminder appended on a schema-validation retry is an extra user message, not a different prompt — it does not change `prompt_version`. Whether an attempt carried the reminder is derived from `attempt_number > 1` plus the preceding attempt's `error_code = 'schema_validation_failed'`; it is not stored.

### Grouping

The `request_correlation_id` groups calls produced by one user action; `attempt_group_id` groups the attempts within one of those calls. Example: a single S12 user turn produces four logical calls that share a correlation id — `stt_transcription`, `roleplay_turn_response`, `roleplay_turn_feedback`, `tts_synthesis` — each of which is one or more attempt rows. This allows end-to-end latency and cost roll-ups per turn.

Counting rule: rows for "how many attempts", `is_final_attempt` for "how many requests", `DISTINCT attempt_group_id` for "how many logical calls".

## Timeout and fallback policy

Timeouts per feature are in the routing table above. Behavior on failure:

| Failure mode               | Action                                                                                     |
|----------------------------|--------------------------------------------------------------------------------------------|
| Schema validation failed   | Retry once with constraint reminder. Second failure → log, surface structured error.       |
| Provider 5xx               | Retry once with 500ms backoff. Second failure → log, surface structured error.             |
| Provider 429 (rate limit)  | Retry once with exponential backoff (1s, then 3s). Second failure → log, surface error.    |
| Timeout                    | No retry. Log, surface error with retry CTA to user.                                       |
| Network error              | Retry once with 500ms backoff. Second failure → log, surface error.                        |

### How each failure mode is recorded

Rows are per attempt (see Logging contract). "Rows" below is the count for one logical call.

| Failure mode | Rows | Terminal `status` | Tokens / cost on the failed rows |
|---|---|---|---|
| First attempt succeeds | 1 | `success` | from the response |
| Schema validation failed, retry succeeds | 2 | `error` then `success` | attempt 1 has tokens (a response arrived, it just did not validate) and its real cost |
| Schema validation failed twice | 2 | both `error` | both attempts have tokens and cost |
| Provider 5xx or network, retry succeeds | 2 | `error` then `success` | attempt 1 has no usable response → tokens and cost `NULL` |
| Provider 5xx or network twice | 2 | both `error` | both `NULL` |
| Provider 429, retries exhausted | 3 | all `error` | all `NULL` (request rejected, nothing billed) |
| Timeout | 1 | `timeout` | `NULL` — no response, so the token count is genuinely unknown |
| TTS cache hit | 1 | `cache_hit` | `estimated_cost_usd = 0`, tokens `NULL` (not a model call) |

A timeout's input tokens may still be billed by the provider even though no response arrived. The contract records `NULL` rather than estimating from prompt length: an unverifiable number in a cost column degrades trust in every other value in that column. The resulting under-count is bounded by the timeout rate, which is itself queryable as `status = 'timeout'`.

A 429 is a rejected request, so nothing is billed — `NULL` here means "no usage reported", and the true value is zero. The distinction from a timeout matters only if provider billing behavior changes; re-check before treating either as authoritative for reconciliation.

User-visible error messages live in `docs/screens/*.md` (S06 for analysis, S12 for roleplay). The pipeline returns a structured error object — UI copy is the screen's responsibility, not the pipeline's.

S07 specifically: the S06 loading modal shows a cancel button at 10 seconds and times out at 60 seconds, matching the `s07_analysis` backend budget above (raised from the storyboard's 30 seconds on 2026-07-25 — see Stage 2). The two must stay equal: a client that gives up first shows the user an error while the backend is still working and still billing the call. On timeout, the modal transitions to an error state in place rather than redirecting.

S12 specifically: TTS failure mid-session continues the session in text-only mode without failing the turn. STT failure (timeout or low confidence) prompts the user to retry the turn — the turn is not consumed against `planned_turns`.

## Cost and latency expectations

### Verified per-unit pricing (May 23, 2026)

| Service | Unit | Price | Source |
|---------|------|-------|--------|
| Claude Sonnet 4.6 (input) | per MTok | $3.00 | [platform.claude.com/docs/en/about-claude/pricing](https://platform.claude.com/docs/en/about-claude/pricing) |
| Claude Sonnet 4.6 (output) | per MTok | $15.00 | same |
| Claude Haiku 4.5 (input) | per MTok | $1.00 | same |
| Claude Haiku 4.5 (output) | per MTok | $5.00 | same |
| Claude Opus 5 (input), eval judge only | per MTok | $5.00 | same, verified 2026-09-05 |
| Claude Opus 5 (output), eval judge only | per MTok | $25.00 | same, verified 2026-09-05 |
| Prompt cache read | per MTok | 0.1× base input | same |
| Prompt cache write (5min) | per MTok | 1.25× base input | same |
| Batch API discount | per request | 50% off | same |
| OpenAI Whisper-1 | per minute audio | $0.006 | openai.com/api/pricing |
| OpenAI TTS-1 (standard) | per 1M chars | $15.00 | openai.com/api/pricing |
| OpenAI TTS-1 HD | per 1M chars | $30.00 | openai.com/api/pricing |

All prices in USD, verified by web search on the date above. Anthropic pricing read directly from `platform.claude.com/docs/en/about-claude/pricing`. OpenAI pricing cross-referenced from multiple secondary sources because `openai.com/api/pricing` returned 403 to the fetcher; rate matches every secondary source consulted, including OpenRouter and TokenMix benchmarks. **OpenAI legacy `whisper-1` / `TTS-1` pricing here is PLANNING-GRADE only** — re-verify from the OpenAI pricing page or the billing dashboard immediately before W4 implementation, and do not use this table for production pricing decisions without that re-verification. Re-verify quarterly.

Note on model ratio: Haiku 4.5 input is $1/MTok vs Sonnet 4.6 input $3/MTok — that is **1/3 the cost on input, 1/3 on output**, not "roughly 1/10" as earlier wording loosely implied. The cheaper-model logic for `roleplay_turn_feedback` still holds, but the actual ratio is 3× rather than 10×.

### Per-action estimates (TTS-1 standard assumed)

Token counts below are project assumptions, to be replaced with measured values during W4-8.

**S07 analysis (text input)**
- Sonnet 4.6: ~900 input + ~270 output tokens
- Cost: (900 × $3 + 270 × $15) ÷ 1,000,000 = $0.00675 ≈ **$0.007 per analysis**

**S02/S04 transcription step (voice input, ≤30 seconds audio)**
- Whisper STT: 0.5 min × $0.006 = $0.003
- Sonnet 4.6 analysis: $0.007
- **Total: ~$0.010 per voice analysis**

**S07 expression playback (TTS, single variant ~80 characters)**
- TTS-1 standard: 80 chars × $15 ÷ 1,000,000 = $0.0012 ≈ **$0.001 per playback**
- Cache hit: no provider charge (data-model.md `tts_audio_cache`)

**S12 roleplay (5-minute session, 5 user turns)**
- Whisper STT: 5 min × $0.006 = **$0.030**
- Sonnet 4.6 (session_init + 5 turn_response + result): ~800 input + ~150 output tokens per call × 7 calls
  - = (5,600 × $3 + 1,050 × $15) ÷ 1,000,000 = **$0.033**
- Haiku 4.5 (5 turn_feedback): ~600 input + ~100 output tokens × 5
  - = (3,000 × $1 + 500 × $5) ÷ 1,000,000 = **$0.006**
- TTS-1 standard (6 coach utterances ~80 chars + result ~200 chars ≈ 680 chars total)
  - = 680 × $15 ÷ 1,000,000 ≈ **$0.010**
- **Session total: ~$0.079**

**Discrepancy note with PPT v2.2 slide 15**: That slide listed $0.110 per session, driven by a $0.045 TTS line. Reconstructing backward, $0.045 implies either ~3,000 TTS characters per session (4× this document's 680-char assumption) or use of TTS-1 HD (2× standard rate). v1 defaults to TTS-1 standard with concise coach utterances, so this document's $0.079 estimate is preferred for planning. Real numbers will be measured from `ai_request_logs.estimated_cost_usd` during W4-8 and this section will be updated with measured medians.

### Cost optimization levers (not used in v1 default)

| Lever | Potential savings | v1 status |
|-------|-------------------|-----------|
| Prompt caching | Up to 90% on cached input tokens | Deferred to W17+ — see Open Question 4 |
| Batch API (50% off) | N/A — PhraseLog calls are interactive, not batch |
| Route simpler tasks to Haiku | 3× savings vs Sonnet on those tasks | Already applied to `roleplay_turn_feedback` |
| TTS content-hash caching | Up to 100% on repeated text | Already designed (data-model.md `tts_audio_cache`) |
| GPT-4o Mini Transcribe ($0.003/min) | 50% cheaper than Whisper | Not selected in v1 (ADR-001 chose Whisper without comparison). Flagged for v1.5+ model comparison phase. |
| TTS-1 standard vs HD | 50% savings using standard | v1 uses standard |

### End-to-end latency targets

Working defaults; tune from `ai_request_logs.latency_ms` data during W4-8:

- S07 analysis: 5 seconds median, 10 seconds p95
- S12 turn round-trip (user audio in → coach audio out): 2-3 seconds median, 5 seconds p95
- TTS-only call: under 1.5 seconds

## Open questions

These items affect pipeline behavior and must be resolved before W4 or during W4-8 prompt iteration:

1. **Coach voice IDs** — `coach_profiles.tts_voice_id` for Mia/David/Sarah. v1 needs three OpenAI TTS voice strings; verification depends on listening tests.
2. **Schema validation library** — Pydantic on the Spring Boot side via a Python sidecar, or a JVM-native JSON schema validator. Affects retry implementation.
3. **STT confidence threshold for retry prompt** — current placeholder 0.5; tune against real user audio quality data.
4. **Prompt cache utilization** — Anthropic prompt caching can reduce S07 cost roughly 50% if the system prompt is reused. Decide whether to commit to caching architecture in v1 or defer to W17+ Model Comparison phase.
5. **Per-stage timeout tuning** — v1 working defaults set above; final values come from W4-8 measured p95 latency.

## Related

- ADR-001 — Split pipeline decision and model selection rationale.
- ADR-003 — Eval system that runs against the LLM stage of this pipeline.
- ADR-007 — Documentation structure; this file is the single source of truth for AI pipeline behavior.
- `data-model.md` — `ai_request_logs`, JSONB column homes, `feature_name` enum.
- `prompts/` — versioned prompt files referenced in the routing table.
- `docs/screens/s06.md`, `s12.md` — user-visible behavior on errors.
- `EVAL_PLAN.md` — eval cases that test schema and quality of LLM outputs.

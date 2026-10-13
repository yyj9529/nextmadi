# Exec plan: measure roleplay result latency and correct the timeout (C6-06)

Status: **On hold (owner decision 2026-10-14) — step 1 partially settled, step 2 needs more samples.**
`ai_request_logs` (queried 2026-09-28) shows the two 2026-09-25 failures at
latency_ms 30091 and 30534 — within ~1.8% of the exact 30000ms read-timeout budget,
which is strong circumstantial evidence for explanation 1 (genuine timeout, misrouted
to `UNKNOWN`) over explanation 2 (unrelated slow failure). This is not a confirmed
root cause: the owner reproduced C6-06 successfully afterward without capturing the
backend console `log.warn` line, so the exact exception type for those two failures
is unrecoverable. `RestClientAnthropicClient.sendMessage`'s generic catch now logs
the `Throwable` itself (not `e.toString()`) so the next occurrence keeps its full
stack trace regardless of console access — see "2026-09-28 finding" below. Steps 2-4
(3+ successful-call sample, timeout value, `docs/AI_PIPELINE.md` update) are not
started; only one genuine (non-mock) successful sample exists so far (22504ms,
2026-09-28 23:49:54). Tracked as [yyj9529/nextmadi#177](https://github.com/yyj9529/nextmadi/issues/177),
modeled on the same pattern as #107 (S07's real-call verification, which led to the 30s→60s
correction). Grounded in the 2026-09-24~28 local test checklist
(`PhraseLog_로컬_테스트_체크리스트.xlsx`, item `C6-06`), `FeatureRouting.java`,
`RestClientAnthropicClient.java`, and `docs/AI_PIPELINE.md` (Timeout/fallback policy,
Logging contract). Blocks #63's pending browser-verification evidence. Backend built in #62.

## Problem

`C6-06` (롤플레이 결과 생성) failed in the local checklist: the result screen shows "결과를
만들지 못했어요" and retry fails the same way. `ai_request_logs` has two `roleplay_result`
attempts, both `status=error`, `error_code=unknown`, `latency_ms` ≈ 30.0s and 30.5s —
right at the `ROLEPLAY_RESULT` timeout (`FeatureRouting.getTimeoutForFeature`, 30s). This
blocks two other checklist items downstream: `C6-07` (result revisit) and `C6-08` (save
from result) are both `N` (unverified) because the result screen never renders.

`FeatureRouting.java`'s own comment already flags this: `ROLEPLAY_SESSION_INIT` and
`ROLEPLAY_RESULT` are "unmeasured... measure before changing" — the same situation S07 was
in before its 30s→60s correction (`docs/AI_PIPELINE.md` line 312, three real calls measured
2026-07-25). This ticket repeats that same measurement discipline for `ROLEPLAY_RESULT`,
per the owner's 2026-09-28 call: measure first, adjust the timeout only from real numbers.

## Open question this ticket must answer before touching the timeout value

`error_code=unknown` is suspicious on its own: `RestClientAnthropicClient`'s
`ResourceAccessException` branch already classifies a `SocketTimeoutException` cause as
`AiErrorCode.TIMEOUT`, not `UNKNOWN` (line ~132-139). If the real failure were a plain read
timeout, the log should read `error_code=timeout`, not `unknown`. Two explanations are open,
and this ticket must find out which one it is before deciding on a timeout number:

1. The call is genuinely timing out, but something in the exception chain isn't the shape
   `ResourceAccessException(cause=SocketTimeoutException)` expects (e.g. it falls through to
   the generic `catch (Exception e)` at the bottom of `sendMessage`, which logs via
   `log.warn("Unexpected error calling Anthropic API: {}", e.toString())` and returns
   `UNKNOWN`). If so, both the timeout value and this classification gap need fixing.
2. The call fails for an unrelated reason that happens to take ~30s (e.g. a large
   conversation-history prompt causing a slow provider response that then fails to parse,
   or a network condition specific to this environment) and the 30s figure is coincidental.

Step 1 below settles this with the actual backend log line (not a re-guess).

### 2026-09-28 finding

`ai_request_logs` for `feature_name='roleplay_result'` (4 rows total, real backend, real
DB `phraselog_dev`):

| created_at | status | error_code | latency_ms |
|---|---|---|---|
| 2026-09-28 23:49:54 | success | — | 22504 (only genuine real-call success so far) |
| 2026-09-25 03:35:30 | error | unknown | 30091 |
| 2026-09-25 03:34:39 | error | unknown | 30534 |
| 2026-07-22 02:34:49 | success | — | 49 (too fast to be a real call — mock, not a sample) |

Both failures cluster right at the 30000ms budget, which favors explanation 1. The
owner then reproduced C6-06 successfully in a live browser session but did not capture
the backend console for either the failing or the passing run, so the exact exception
type is not confirmed from a real log line — "it started working" is not evidence of
a fixed root cause here, only that this particular attempt did not hit whatever
condition caused the 9/25 failures. Applied as an interim measure: changed
`RestClientAnthropicClient.sendMessage`'s generic `catch (Exception e)` to log the
`Throwable` via SLF4J (`log.warn("...", e)`) instead of `e.toString()`, so the next
occurrence's full stack trace is captured in whatever log sink is configured, closing
the "backend console window is unreachable/ephemeral" gap that blocked this step.
Verified via `./gradlew spotlessApply spotlessCheck test` (462 tests, 0 failed, 117
skipped — Testcontainers, expected locally). Open question in the "Open question"
section above is still **not closed**; next real failure's stack trace settles it.

### 2026-10-14 finding (code reading, not yet reproduced)

Explanation 1 as worded above has a gap against the current code. `AnthropicService`
does not retry `TIMEOUT` and logs it through `statusForErrorCode` as `status=timeout`
with `error_code=timeout`. The two 2026-09-25 rows are `status=error`,
`error_code=unknown`, so they did not pass through the `ResourceAccessException` branch
that classifies a read timeout. A plausible path is a read timeout while the response
**body** is being read: Spring `RestClient` wraps that as a `RestClientException`, not a
`ResourceAccessException`, which lands in the generic `catch (Exception e)` and becomes
`UNKNOWN`. This is an unverified hypothesis; the Spring behavior has not been confirmed
against Spring Boot 3.5.15.

Cheapest check, no Claude call and no cost: point a `RestClient` built with
`SimpleClientHttpRequestFactory` (read timeout 1s) at a local JDK `HttpServer` that sends
headers and then stalls the body, and record the exception type that surfaces. If it is a
`RestClientException` with a `SocketTimeoutException` cause, step 3 gets a concrete fix
(classify by cause chain) before any timeout value is touched.

## Design

### 1. Capture the real failure mode

- Reproduce one `C6-06` failure locally with a valid `ANTHROPIC_API_KEY` (confirm the key
  is valid first — 2026-09-25's roleplay investigation found an earlier failure was caused by
  an invalid key returning Anthropic 401, unrelated to this one; do not re-diagnose that).
- Capture the backend log output for that request. `RestClientAnthropicClient` logs
  `log.warn` only on the final generic catch — if that line fires, its `e.toString()` names
  the real exception type and settles the open question above immediately.
- Cross-check against the `ai_request_logs` row for the same `attempt_group_id`
  (`error_code`, `latency_ms`, `status`).

### 2. Measure real latency (same method as S07)

- Run several real (not mock) `ROLEPLAY_RESULT` calls end-to-end — complete a roleplay
  session through to `POST /practice/sessions/{id}/result` — and record `latency_ms` from
  `ai_request_logs` for `feature_name = 'roleplay_result'`, `status = 'success'`.
- Target at least 3 successful timed calls, matching the sample size S07 used
  (`docs/AI_PIPELINE.md` line 312 cites three: 25.7s/27.8s/35.1s). If the prompt varies a
  lot in session length (`planned_turns` 3–10), include at least one short and one long
  session so the sample isn't biased toward the fast end.
- If step 1 shows the failures are genuine timeouts, these successful-call timings still
  matter: they show how close to 30s a *normal* call already runs, which is the same signal
  S07 used to justify 60s.

### 3. Fix what step 1 found

- If the exception is falling through to `UNKNOWN` instead of `TIMEOUT`: fix the
  classification in `RestClientAnthropicClient.sendMessage` so a real read timeout is
  reported as `AiErrorCode.TIMEOUT` regardless of which catch branch it lands in. Add a
  regression test mirroring the existing `AnthropicServiceTests`/client tests for the
  `TIMEOUT` path.
- If it's a different root cause (e.g. a parse failure on a slow-but-successful response),
  fix that specific bug instead — do not touch the timeout value to paper over a different
  failure.

### 4. Set the timeout from the measured numbers

- Using the step 2 sample, pick a budget the same way S07's comment justifies 60s (a figure
  that sits above the observed high end, not the mean). Update
  `FeatureRouting.getTimeoutForFeature` for `ROLEPLAY_RESULT` and update the class-level
  Javadoc comment to record the new measured basis (sample values + date), replacing the
  "unmeasured... measure before changing" language for this feature only —
  `ROLEPLAY_SESSION_INIT` stays flagged unmeasured, this ticket does not touch it.
- Update `docs/AI_PIPELINE.md`'s roleplay timeout line (currently "working default,
  unmeasured") with the new value, the measured sample, and the date — same provenance
  format as the S07 correction it documents at line 312.
- Decide whether the frontend needs a client-side abort budget for the result screen (S07's
  `TryExperience.tsx` pattern, `#128`) — only if the result screen currently has no client
  timeout and could hang past the new backend budget. Check
  `RoleplayExperience.tsx`/the result page before assuming this is needed; do not add it
  speculatively.

### 5. Re-verify the blocked checklist items

- `C6-06`: result screen renders after a completed session, backed by a real call within
  the new budget.
- `C6-07`: revisit the result screen without triggering regeneration (uses the saved
  `resultJson`, already implemented in `PracticeResultService.generateResult` — just needs
  `C6-06` unblocked to actually exercise it).
- `C6-08`: save a recommended expression from the result screen.

## Files expected to change

- `backend/src/main/java/com/phraselog/ai/client/config/FeatureRouting.java` (timeout value
  + comment).
- `backend/src/main/java/com/phraselog/ai/client/service/RestClientAnthropicClient.java`
  (only if step 1 confirms a classification bug) + its test file.
- `docs/AI_PIPELINE.md` (roleplay result timeout line, with source/date).

No DB migration, no API contract change, no frontend change unless step 4's abort-budget
check says otherwise.

## Verification

- `./gradlew spotlessApply spotlessCheck test` (backend gate, per
  `docs/solutions/backend-ci-gate-spotless.md`).
- Re-run the specific failing checklist rows (`C6-06`, `C6-07`, `C6-08`) against a live
  backend with a valid Anthropic key.
- `docs/quality-gates.md` "S12 roleplay" gate: turn-state tests pass, result schema
  validates, cost per session logged.

## Risks / review notes

- This is an AI-pipeline timeout/error-classification change → two-agent handoff applies
  (CLAUDE.md section 9): independent review by a different lineage before merge, written to
  `docs/reviews/`.
- Do not raise the timeout without the step 1/2 evidence — that would repeat exactly the
  mistake `FeatureRouting.java`'s own comment warns against (guessing from S07's numbers).
- If step 1 finds the failure is unrelated to timeout (explanation 2 above), this plan's
  scope shrinks to a bug fix and the timeout may not need to change at all — say so plainly
  rather than changing the number anyway to look like progress.

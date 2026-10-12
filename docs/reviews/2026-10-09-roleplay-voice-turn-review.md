# Review - Roleplay Voice Turn (C6-04)

- **Date:** 2026-10-09
- **Scope:** `feat/roleplay-voice-turn` (uncommitted at review time)
- **Target:** roleplay voice input via multipart `audio` on `POST /practice/sessions/{id}/turns`, plus
  reopening a `failed` idempotency row in `reserveRequest`
- **Plan:** `docs/exec-plans/2026-10-09-roleplay-voice-turn.md`
- **Reviewer:** independent spec-reviewer agent, gate 1 (spec compliance). It did not write the code.
- **Verdict:** **Approve with changes.** One blocker (finding 1), fixed after the review; owner questions open.

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | Major (blocker) | Client reset the idempotency key on any HTTP response while keeping the audio for retry. When the BFF gives up at 90s (502) the backend keeps running and can commit the turn; retrying with a new key then records the answer twice. | Fixed: key is reset only on definite outcomes (`src/lib/practice/turn-outcome.ts`, tested). Other statuses keep the key; the backend reopens `failed` rows and replays finished ones. |
| 2 | Minor | `markFailed` has no `WHERE status = 'processing'`. If a commit lands but the driver reports failure, a `completed` row can become `failed` and a same-key retry would now reopen it and append a second pair. | Fixed: guard added, repository test `markFailedDoesNotOverwriteAFinishedRequest` |
| 3 | Minor (spec) | Low-confidence `retry_prompt` is the server's English constant; s12 US2 AC5 says the UI shows the Korean "잘 안 들렸어요. 다시 말해주세요". The 422 path shows a third wording. | Fixed by owner decision (Korean): both paths show the fixed Korean copy; the server text is no longer rendered |
| 4 | Minor | No `spring.servlet.multipart` limit set (Spring default 1MB per file, estimated ~930KB for 58s). An oversize upload becomes a 500, the BFF returns 502, and the client offers a retry that fails the same way. | Fixed: limits set to 4MB/5MB to match the BFF cap, and `MaxUploadSizeExceededException` maps to 413 `payload_too_large` (advice test). Still verify with a real 58s recording. |
| 5 | Minor | `openapi.yaml` lacks 408/422/429/503 (and BFF 413/504) for this endpoint and does not say a failed key is re-executed; `data-model.md` does not say `failed` can return to `processing`. | Fixed: openapi turns section (idempotency and voice notes, 408/413/422/429/503) and data-model updated. BFF-only 504 is not a backend contract and is not listed. |
| 6 | Minor | No tests for `sendTurn` key/payload logic or the two-thread reopen claim; `submit-turn` timeout untested; one vacuous assertion (`PLACEHOLDER_TRANSCRIPT` length) in the adapter test. | Partly addressed (turn-outcome test); rest open |
| 7 | Minor | Pending `router.replace` timers and the turn fetch are not tied to component lifetime; a long voice turn can pull the user to the result screen after they left. Pre-existing, window is longer now. | Open |
| 8 | Minor | After an unknown-outcome failure, switching to a new payload can leave the screen out of sync with a turn the server did commit. Re-fetch session state before accepting a new payload. | Open |
| 9 | Nit | s12.md US2 AC1 still said "waveform"; reopen overwrites the failed attempt's correlation id; mic looks tappable in failure states but `tap` is ignored there; size check runs after the body is buffered; multipart branch relies on Origin + SameSite for CSRF; client collapses BFF 429/504 into one message. | AC1 wording fixed; rest open |

## Adapter check (no defects found)

`onTranscript` fires exactly once per recording through the adapter, synchronously after its response, so
the single-slot `takeAudio` never holds two recordings. Cancel, permission denial, unmount mid-recording and
StrictMode revival behave correctly. The new reopen SQL is correct for two concurrent retries and for a retry
racing a live attempt (zero rows, `DuplicateKeyException`).

## Could not be verified

- Real end-to-end backend latency and whether it exceeds 90s in production; deployment function duration and
  body limits.
- Spring's multipart limit with a real 58s recording.
- Postgres lock behaviour under real concurrency (reasoned, not run).
- Browser behaviour: mic permission, MediaRecorder output size, StrictMode timing.
- The low-confidence path end to end (local mock STT returns null confidence).

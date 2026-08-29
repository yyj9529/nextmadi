# Review: Voice input flow (#36 / PR #134)

- **Ticket:** #36 - Voice input flow (record -> transcribe -> confirm).
- **Implementer:** Claude (Opus 5).
- **Reviewer:** Codex, independent reviewer and sandbox tester.
- **Exec-plan:** `docs/exec-plans/2026-08-04-ticket-36-voice-input-flow.md`.
- **Gate:** AI path + anonymous auth + paid external API surface. Two-gate review per
  CLAUDE.md section 9: spec compliance first, code quality second.
- **Scope note:** I did not read
  `docs/reviews/2026-08-27-ticket-36-voice-input-flow-review.md` before writing this
  file.

## Scope reviewed

Reviewed `origin/main...origin/feat/36-voice-input-flow` at feature ref
`638738dc32456f7e55a3b5faa5ab9e20c5663f86` against:

- `docs/screens/s02.md`, `docs/screens/s04.md`, `docs/screens/s05a.md`,
  `docs/screens/s06.md`
- `docs/api/openapi.yaml` `POST /transcriptions`
- `docs/AI_PIPELINE.md` Stage 1
- ADR-010 (`docs/decisions/010-bff-auth-handoff.md`)
- `SECURITY.md`
- Existing backend transcription implementation from ticket #29, because the new BFF
  depends on its error semantics.

I reviewed the implementation via an isolated temporary worktree rather than switching
the shared checkout, because the local workspace was on `main` and already had an
unrelated `.claude/settings.json` modification.

## Verification run

- `bun test` - 166 pass, 0 fail.
- Targeted voice/BFF tests - 43 pass, 0 fail.
- `bun run typecheck` - pass.
- `bun run lint` - pass.
- `bun run build` - pass after replacing my temporary `node_modules` junction with a
  real offline install in the isolated worktree. The earlier junction failure was a
  Turbopack environment issue, not a code failure.
- `backend/gradlew.bat test --tests
  com.phraselog.transcription.service.WebmOpusInspectorRealRecordingTests` - 2 pass,
  0 fail, 0 skipped.
- `backend/gradlew.bat test` - 391 tests, 390 passed, 0 failed, 1 skipped
  (`OpenAiTranscriptionLiveTest > transcribesRealWebmOpusFixture()`).

## Findings

| # | Severity | Finding | Failure scenario | Recommendation |
|---|----------|---------|------------------|----------------|
| 1 | P1 blocker | Empty STT responses cannot reach the specified empty-transcript UX because Spring returns them as 400 `validation_failed`, while the new BFF/client only treat 422 as `empty_transcript`. Evidence: Spring trims the provider text and throws `validationFailed("transcript must not be blank.")` on blank text at `backend/src/main/java/com/phraselog/transcription/service/TranscriptionService.java:67-70`; the backend test fixes that as 400 `validation_failed` at `backend/src/test/java/com/phraselog/transcription/service/TranscriptionServiceTests.java:126`; the new BFF only emits `empty_transcript` when `TranscribeError.isEmptyTranscript` is true at `src/app/api/transcriptions/route.ts:112`, and `isEmptyTranscript` is defined as status 422 at `src/lib/voice/transcribe.ts:69`; the browser maps 400/413 to `invalid_audio` at `src/lib/voice/use-voice-recorder.ts:271`. | A user taps the mic, says nothing or Whisper returns whitespace. The backend returns 400 `validation_failed`; `transcribeAudio` classifies all 400s as invalid audio; the UI shows the invalid-recording path instead of the S02/S04 "speech not understood, try speaking again" path. AC5 is not actually met for the real backend. | Make the backend return a distinct empty-transcript status/code, or make the BFF classify backend 400 with `developer_hint`/`error_code` for blank transcript separately from audio-format validation. Add an integration-style test using the real backend 400 shape, not only a mocked 422. |
| 2 | P1 blocker | Anonymous `/api/transcriptions` exposes paid Whisper calls without any application quota or abuse gate. Evidence: the route accepts anonymous callers, mints a new anonymous cookie if none exists, and immediately calls `transcribeAudio` at `src/app/api/transcriptions/route.ts:73-88`; its only local limit is a 4 MB body cap at `src/app/api/transcriptions/route.ts:22` and `src/app/api/transcriptions/route.ts:64`; the anonymous daily limit exists only around `POST /analysis` via `withAnonymousAnalysisLimit` at `backend/src/main/java/com/phraselog/analysis/service/AnalysisService.java:95` and `AnonymousAnalysisUsageService` reserves two per day at `backend/src/main/java/com/phraselog/usage/service/AnonymousAnalysisUsageService.java:14` and `backend/src/main/java/com/phraselog/usage/service/AnonymousAnalysisUsageService.java:51`. No equivalent reservation appears in the transcription BFF or backend transcription service. | An unauthenticated caller can repeatedly POST up to 4 MB WebM files to `/api/transcriptions`, receive a fresh anonymous cookie when needed, and burn OpenAI Whisper spend without consuming the two anonymous analysis slots. Origin checks stop browser CSRF, but direct same-origin/curl-style calls with no Origin are accepted by design. | Add a dedicated STT quota before the provider call, probably IP/day plus session token and/or a stricter anonymous pre-analysis budget. Keep the current 2-step guarantee by not charging the analysis quota on failed transcription, but do not leave STT unmetered. |
| 3 | P2 should-fix | S02's S06 cancel button does not abort the in-flight `/api/analysis` request. Evidence: `postTryAnalysis` creates its fetch with `AbortSignal.timeout(...)` only at `src/app/(app)/try/TryExperience.tsx:65`; `handleCancel` only increments `activeSubmitRef` and clears the modal at `src/app/(app)/try/TryExperience.tsx:135`; the modal receives that handler at `src/app/(app)/try/TryExperience.tsx:207`. By contrast, S05a uses an `AbortController` and aborts on unmount at `src/components/app/AnalysisModals.tsx:190` and `src/components/app/AnalysisModals.tsx:247`. | A S02 voice user records, confirms the transcript, submits, waits 10 seconds, then taps cancel. The UI dismisses the loading modal, but the server request can continue, create an `analysis_requests` row, call Claude, and consume anonymous quota/cost. If the user retries, the app can send another request while the first one is still running. | Thread an `AbortSignal` through `TryAnalysisSubmitter`, use a per-submit `AbortController`, and abort it from `handleCancel`. Preserve the current stale-result guard as a UI safety net, but do not rely on it as cancellation. |
| 4 | P2 should-fix | MediaRecorder startup/runtime failures are not converted into a recoverable machine state. Evidence: the recording effect constructs `new MediaRecorder(...)` at `src/lib/voice/use-voice-recorder.ts:139`, assigns handlers, and calls `recorder.start()` without a surrounding error transition; there is also no `recorder.onerror` handler or track `onended`/`ended` listener in the hook. `releaseMicrophone` exists at `src/lib/voice/use-voice-recorder.ts:72`, but it is driven by status changes or unmount. | If `getUserMedia` succeeds but the track is ended before `MediaRecorder` starts, `MediaRecorder.start()` throws, or the recorder emits a runtime error after a physical mic disconnect / permission revocation, the hook can remain in `recording` without dispatching a failure or stop event. The visible state can say it is still listening until unmount or manual navigation, and this edge is not covered by the current tests. | Wrap MediaRecorder construction/start in `try/catch`, dispatch an invalid/transient recording error on failure, release the stream immediately, and attach `recorder.onerror` plus track-ended handlers that route to the same cleanup path. Add hook-level tests with fake recorder/track failures; the reducer-only tests do not cover this resource path. |
| 5 | P3 nit | S02 documentation still says the browser stores `phraselog_session_token` in sessionStorage, while the actual BFF pattern uses an httpOnly cookie. Evidence: `docs/screens/s02.md:4` and `docs/screens/s02.md:29` still describe sessionStorage, while the shared constant is `phraselog_anon_session` at `src/lib/anon-session.ts:6` and the new transcription route reads/sets that cookie at `src/app/api/transcriptions/route.ts:76` and `src/app/api/transcriptions/route.ts:100`. The exec-plan decision log explicitly says the implementation should use the existing httpOnly cookie. | A future implementer or reviewer following S02 literally may reintroduce a browser-readable token or write tests against sessionStorage, diverging from ADR-010's BFF handoff. | Update S02 to record the httpOnly-cookie reality, or explicitly mark the old sessionStorage wording as superseded by #34/#35. |

## Acceptance criteria check

| AC | Verdict | Notes |
|---|---|---|
| 1. S02 mic -> record -> stop -> transcribe -> transcript editable -> text-only `/analysis` | Conditional pass | The code fills the textarea via `onTranscript` and posts JSON to `/api/analysis`; audio does not go to `/analysis`. Blocked only by Finding 1 for the empty transcript sub-path. |
| 2. S04 mic -> same flow -> S05a prefilled | Pass | `HomeAnalysisCard` opens `TextInputSheet` with the transcript as `initialText`; S05a owns the submit path. I did not run browser QA for logged-in S04. |
| 3. Stop conditions: re-tap / VAD silence / 60-second hard cut | Pass for nominal path | Reducer and VAD tests cover re-tap, silence, no-speech, and max-duration transitions. Browser resource edge cases remain in Finding 4. |
| 4. Permission denied and unsupported browser guide to text input | Pass | Capability gate checks WebM/Opus before permission, and the UI provides text-input escape. |
| 5. Empty transcript retry; 429/timeout/network retryable failure | Fail | Retryable failures are handled, but real backend empty transcripts are 400 and become invalid-audio UI, not empty-transcript UI. |
| 6. Mic tracks released on stop/error/unmount | Conditional pass | Nominal stop, cancel, and unmount cleanup are present. MediaRecorder startup/runtime failure and physical device edge cases are not handled explicitly. |
| 7. Anonymous S02 uses `session_token`, logged-in S04 uses `user_id`, XOR | Pass | BFF route and `transcribeAudio` preserve XOR. Tests verify both token shapes. |

## Additional gate checks

- **Two-step contract:** Confirmed for implemented paths. S02/S04 audio goes to
  `/api/transcriptions`; confirmed text then goes to `/api/analysis`. Transcription
  failure does not reserve the anonymous analysis quota because that quota is only inside
  `AnalysisService.create`. This is good, but it creates the separate STT quota gap in
  Finding 2.
- **Transcript leakage:** Checked. The new TS/BFF path has no `console` logging, and the
  backend STT logger records `feature`, `model`, latency, status, cost, and correlation
  id, not transcript text. No transcript leak found in logs, errors, telemetry, or
  persistence introduced by this PR.
- **BFF security consistency:** Origin check, anonymous cookie handling, internal auth
  token minting, and size cap are consistent with the existing BFF style. Type validation
  is mostly delegated to the backend WebM inspector; acceptable for correctness, but it
  does not address abuse without a quota.
- **Spec changes:** The waveform removal and S04/S05a/S06 modal-flow edits are recorded
  in the exec-plan Decision log as owner decisions, not just implementation drift. The
  remaining sessionStorage wording in S02 is stale and should be corrected.
- **Test quality:** The pure reducer/VAD/capability tests are meaningful for their layer.
  The BFF/transcribe tests are useful for route behavior but miss the cross-layer 400 vs
  422 empty-transcript mismatch because they mock the desired 422 shape. There are no
  hook-level fake MediaRecorder tests for constructor/start/error/track-ended cleanup.

## Gate verdicts

- **Gate 1 verdict:** FAIL - the main 2-step flow is implemented, but AC5 fails against
  the real backend because empty transcripts are misclassified.
- **Gate 2 verdict:** FAIL - the code is mostly well structured and local tests pass, but
  anonymous STT has no quota on a paid provider path, and resource edge cases are not
  fully covered.

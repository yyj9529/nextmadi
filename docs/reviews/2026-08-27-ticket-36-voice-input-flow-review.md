# Review: Voice input flow — record → transcribe → confirm (#36)

- **Ticket:** #36 — E05.5 Voice Input Flow, shared by S02/S04. PR #134.
- **Implementer:** Opus 5 (owner-approved deviation from the card's Sonnet 4.6).
- **Reviewer:** Opus 5, two independent read-only contexts (spec-compliance and
  code-quality), neither carrying the implementation context.
- **Exec-plan:** `docs/exec-plans/2026-08-04-ticket-36-voice-input-flow.md`.
- **Gate:** AI pipeline + anonymous auth (CLAUDE.md 9, `docs/quality-gates.md`).
  Two-gate: spec compliance, then code quality.

**Reviewer-independence caveat.** CLAUDE.md 9 requires the reviewer to be a different
model family from the implementer. Both gates here ran on Opus 5 — the same family that
wrote the code — in separate contexts with no implementation memory. That is stronger
than the implementer's own self-check recorded in the exec-plan, but it is **not** the
Codex review the protocol asks for. The two P1s below were independently re-verified
against source by the orchestrating session before being recorded.

## Scope reviewed

`git diff origin/main...origin/feat/36-voice-input-flow` — 24 files, +2176/-105.
New `src/lib/voice/*` (state machine, VAD, capability gate, transcribe lib),
`src/app/api/transcriptions/route.ts`, `src/components/app/VoiceInput.tsx`; rewired
`TryExperience`, `HomeAnalysisCard`, `AnalysisModals`; backend real-recording fixtures;
edits to `docs/screens/s02.md`, `s04.md`, `s05a.md`, `s06.md`.

Checked against `docs/screens/s02.md` US1-4, `s04.md` US1-3, `s05a.md`, `s06.md`,
`docs/api/openapi.yaml` `POST /transcriptions`, `docs/AI_PIPELINE.md` Stage 1, ADR-010,
`SECURITY.md`, and the exec-plan's own seven acceptance criteria (treated as claims to
verify, not as evidence).

## Passes (verified, not assumed)

- **XOR internal auth per ADR-010.** Runtime XOR in `src/lib/voice/transcribe.ts`,
  type-level XOR in `src/lib/internal-auth.ts`, Origin check and httpOnly cookie
  handling identical to `src/app/api/analysis/route.ts`. Three route tests cover it.
- **Transcript never logged.** Zero `console`/logging calls on the transcript in the new
  frontend and BFF code; error surfaces carry `error_code` only; the backend `logCall`
  records metadata only. SECURITY.md logging discipline holds.
- **Two-step contract holds.** Audio goes only to `/api/transcriptions`; a failed
  transcription does not burn the anonymous 2-analysis allowance — the reason the
  2026-06-10 decision exists.
- **Resource release on the normal, cancel, and unmount paths is correct.** The
  `holdsMicrophone` transition-based release plus unmount cleanup closes tracks,
  AudioContext, and intervals. Effect ordering verified. React StrictMode double-invoke
  is safe at mount (all effects early-return at `idle`).
- **Abort-reason discrimination is correct** — timeout abort routes to transient, user
  cancel is ignored; no unhandled rejections.
- **Capability gate precedes the permission prompt**, which is the right order for the
  Safari dead end.
- **Timeout layering is ordered correctly:** backend 30s < route 40s < client 45s.
- **timeslice prohibition** in the recorder matches the measured backend inspector
  behavior recorded in the exec-plan's risk 1.

## Blockers

**B1 (spec) — The empty-transcript path cannot reach its own spec text.**
`docs/screens/s02.md` states `STT returned nothing → "말소리를 알아듣지 못했어요.
다시 말해볼까요?" + 다시 녹음`, and the issue body requires "STT empty result: prompt
retry". The backend throws `validationFailed("transcript must not be blank.")` — a
**400**, the same code as malformed audio (`TranscriptionService.java`). The frontend
classifies every 400 as `isInvalidAudio` (`transcribe.ts`), so a user who said nothing
gets `"녹음이 올바르지 않아요. 다시 녹음해주세요."` The `422` branch fires only on a
`200` + blank body, which the backend never sends — it is dead in the real path. The VAD
8-second no-speech stop, the most common failure, lands here. The exec-plan's own state
machine said `200 + 빈 transcript / 400 → error_empty`; the implementation split the 400
off without recording the change. **Re-verified against source.**

**B2 (quality + spec, converged independently) — Anonymous `/api/transcriptions` has no
usage limit.** The route requires neither login nor a pre-existing anonymous cookie — it
mints one with `randomUUID()` when absent, so a caller holding no state passes. Backend
rate limiting exists only in `AnalysisService`/`PracticeSessionService`; the
`transcription` package references `AnonymousAnalysisUsage*` nowhere (grep-verified).
`transcribe.ts` does not forward `x-client-ip`, unlike `submit-analysis.ts`, so an
IP-based limit added later would have no IP to key on. `openapi.yaml` already lists
`429 RateLimitExceeded` in the contract, so the gap is a contract shortfall, not just a
design choice. This PR is what first makes the endpoint reachable from a browser. Whisper
is metered per request. **Re-verified against source.**

B2 is the kind of unstated trade-off CLAUDE.md says not to decide silently: either forward
the IP and add a backend limit, or record an explicit owner decision to accept the exposure
for MVP.

## Should-fix

**S1 (spec) — S02 has no "텍스트로 입력하기" CTA** on mic-unavailable states. `s02.md` and
acceptance criterion 4 both require it. `TryExperience` renders `<VoiceInput>` without
`onUseText`, so the CTA never mounts; only S04 passes it. On `unsupported` (which by design
offers no retry) the notice ends up with no action button at all.

**S2 (quality) — `stopping` can deadlock, and `MediaRecorder.onerror` is unhandled.** If a
track ends mid-recording (permission revoked, USB mic unplugged), the recorder self-stops
while the machine is still `recording`, so the reducer drops `blob_ready`. A later tap
enters `stopping`, where the recorder is already `inactive` and no branch fires — the UI
sits in "마무리 중..." with taps ignored and no cancel affordance. Only a page refresh
escapes, and the mic stays held. No `onerror` handler exists at all, which reaches the same
deadlock via the 60s cut.

**S3 (spec) — The 60s hard cut can produce audio the backend rejects.** The frontend stops
at `elapsed >= 60_000` on a 100 ms poll plus `stop()` latency; `WebmOpusInspector` rejects
`durationSeconds > 60.0`. A hard-cut recording can land at 60.0–60.2 s and return 400.
Acceptance criterion 3's third stop condition may therefore not reach a success path. The
browser verification covered one clean round trip and one permission denial, not this
boundary.

**S4 (quality) — `new MediaRecorder` / `recorder.start()` are unguarded.** The AudioContext
creation immediately below degrades gracefully in a `try/catch`, but the more throw-prone
calls are bare. An `InvalidStateError` thrown from an effect unmounts the React tree — a
blank screen in a PR whose whole design principle is "no dead ends".

**S5 (both, converged) — The 321-line hook that owns every resource has zero tests.** The
exec-plan's test plan specified injectable browser APIs and hook-level transition tests
(permission denial, 60s cut, VAD stop, empty transcript, 429, abort). No
`use-voice-recorder.test.ts` exists; the hook reaches for `new MediaRecorder`,
`navigator.mediaDevices`, and `new AudioContext` as globals, so it is not injectable. All
28 new tests cover pure modules and the route. S2 is exactly the branch such a test would
have caught. This is a silent reduction from the plan.

**S6 (spec) — S04 does not truncate a transcript over 500 characters.** S02 slices to
`MAX_INPUT_LENGTH`; S04 passes it through to `TextInputSheet`, which does not bound its
initial value and gates submit only on empty. A 501+ character transcript shows "540 / 500"
and fails submit with `"연결이 불안정해요."` — a misleading error. A 60-second utterance
approaches 500 characters. `s04.md` does not define this case.

**S7 (quality) — `maxDuration` is not declared on the route.** It budgets 40 s and the
client waits 45 s, but Vercel's Node default (10–15 s by plan) would cut a slow Whisper call
first, surfacing only "변환에 실패했어요". No `maxDuration` exists anywhere in the repo, so
this matches the existing pattern — but a 60-second Whisper call is a realistic overrun.
Flagged as pre-deploy, plan-dependent.

## Nits

- Mic button stays enabled in failure states while the reducer ignores `tap`; map `tap` to
  retry, or disable it.
- `jsonError`, `isSameOrigin`, and the anonymous-cookie block are duplicated verbatim from
  `api/analysis/route.ts`; `subjectFor` is duplicated across the two libs. Worth extracting
  before a third BFF route appears.
- Size validation runs after `formData()` has buffered the whole body; a `content-length`
  pre-check would avoid buffering large bodies on an unauthenticated path.
- No MIME check in the route — it relies entirely on the backend inspector.
- `route.test.ts` substitutes a `FakeTranscribeError` that re-implements the status →
  classification mapping, so it would keep passing if the real classifier changed.
- `recorder-machine.test.ts`'s final case is near-tautological: its `continue` structure
  leaves only `stopping` able to fail, so it re-checks the pure `holdsMicrophone` helper
  rather than actual release.
- 10 Hz re-render during recording (elapsed timer at 100 ms) re-renders the whole parent
  form, and the `aria-live="polite"` caption re-announces continuously.
- `TryExperience` overwrites already-typed user text with the transcript, without warning.
- The transcript is written to the localStorage draft on sheet close — consistent with
  existing text handling, but voice data in the draft store is not specified anywhere.
- `s06.md` lost its "times out at 30s" figure with no matching Decision-log entry.
- `s02.md` still describes the anonymous token as sessionStorage `phraselog_session_token`
  while the implementation uses the httpOnly cookie; the exec-plan acknowledged the split
  but this PR edited `s02.md` without fixing it.
- The new STT error rows added to `s02.md`/`s04.md` are not in the Decision log — the
  recorded owner decisions are the waveform removal, the S06/S05a resolution, and the iOS
  Safari gate. The content looks right; the provenance is unrecorded.
- Dev-mode StrictMode double-invoke can fire the transcribe effect twice. Zero cost under
  the local mock; worth remembering for live-key verification.
- Dead export: `mockAnalysisResultPath`.

## Codex cross-check (2026-08-27)

An independent Codex review ran afterward and is recorded separately at
`docs/reviews/2026-08-27-ticket-36-codex-review.md` (written from the main checkout,
`C:/Users/ywj95/Desktop/nextmadi`).

Codex could not read this file during its pass — this review lives uncommitted in the
`madi-1` worktree, and the prompt named the path without naming the checkout. The effect
was accidentally useful: Codex verified B1 and B2 **from source with no exposure to this
document**, so the agreement below is genuine convergence rather than confirmation of a
text it had already read.

- **B1 confirmed independently.** Codex reached the same conclusion by the same route:
  Spring returns 400 `validation_failed` for a blank transcript, the new client maps only
  422 to `empty_transcript`, so the spec's "말소리를 알아듣지 못했어요" path is unreachable.
- **B2 confirmed independently.** Anonymous `/api/transcriptions` has no app-level quota
  beyond the 4 MB body cap; the Whisper cost surface is open.
- **Converged on two should-fix items without seeing them here:** the unhandled
  `MediaRecorder` constructor/start/runtime-error and track-ended edges (S2, S4), and the
  `s02.md` sessionStorage-vs-cookie documentation drift (nit).
- **Codex merge verdict: do not merge.** Minimum: B1 and B2.

**One finding Codex added — real, but out of scope for this PR.** S02's cancel button does
not abort the in-flight `/api/analysis` request: `handleCancel` in `TryExperience` only
increments the `activeSubmitRef` generation counter and hides the modal, while
`submitAnalysis(text)` is called with no signal. The Claude call runs to completion, so the
LLM cost is spent and the anonymous 2-per-day allowance is consumed while the user believes
they cancelled. S05a does this correctly with an `AbortController` in `TextInputSheetBody`.
Verified against `origin/main`: `handleCancel` is byte-identical there, so this predates
PR #134 and is not a regression from it. It belongs in its own ticket, not in #36's merge
gate.

**Test-count discrepancy.** Codex measured `bun test` 166/166 pass and backend 390 pass /
1 skipped. The exec-plan recorded 159 pass / 1 fail, attributing the failure to an unrelated
pre-existing `getLandingExamples` case. The gap is unexplained; whoever lands this should
confirm which ref the exec-plan's numbers came from rather than assuming the record is
current.

## Verdict

**Gate 1 (spec compliance): CONDITIONAL PASS.**
**Gate 2 (code quality): CONDITIONAL PASS.**
**Codex independent review: do not merge (B1, B2).**

The high-risk axes the gate exists for — XOR internal auth, transcript non-logging, the
two-step contract, and resource release on the ordinary paths — hold up under independent
reading. Merge is blocked on the two P1s: the empty-transcript path does not reach its own
spec text, and a metered STT endpoint is open with no limit. S1–S6 should be resolved or
explicitly accepted by the owner before merge.

Acceptance criterion 2 (S04 prefill) remains unverified in a browser; the exec-plan records
the same gap. Criteria 3 and 6 have no automated evidence either, because the hook has no
tests (S5).

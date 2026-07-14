# Exec plan: S12 roleplay screen real-data wiring (#61)

Status: **In progress.** Grounded in GitHub issue #61, `docs/screens/s12.md`,
`docs/api/openapi.yaml`, the closed backend tickets #59/#60, and the current frontend
checkout (based on the #47 branch, see "Branch base" below).

## Goal

Replace the S12 mock roleplay pass (`mockSession` + `mockTurnScript` played on a
`setTimeout`) with the real session lifecycle:

- Start a session from S09 ("이 표현으로 연습하기") via `POST /practice/sessions`.
- Load real session state on the S12 screen via `GET /practice/sessions/{id}`
  (opening turn, `planned_turns`, restore prior turns on refresh/return).
- Submit each user turn via `POST /practice/sessions/{id}/turns` and render
  `user_turn` + optional `feedback` + `coach_turn`, honoring `turn_consumed=false`
  (retry prompt) and `session_status=completed` (auto-route to result).

Backends for all three endpoints are closed (#59 session start/get, #60 turn pipeline).
This ticket is the **frontend/BFF wiring only** — no backend or provider change.

## Branch base

`origin/main` (55bae61) does not yet contain #47 (S09 real-data). #61's session-start
step edits S09's practice button, which #47 owns. To avoid editing a soon-to-be-replaced
mock S09 and to avoid a merge conflict, this branch is stacked on the #47 tip (9654e74).
The #61 PR lands after #47 merges. Flagged to the owner.

## Scope (owner-confirmed 2026-07-14)

- **In:** session start (from S09), session fetch/restore, turn submit (text path),
  feedback/retry/completion UX, coach-name resolution, TTS autoplay when the turn
  response carries `tts_audio_url`.
- **Out:** real microphone capture (MediaRecorder/#36 reuse). The voice button is kept
  visible but disabled with a "음성 입력 준비 중" affordance; text input is the live path.
  Deferred to a follow-up so this ticket stays reviewable.
- **Model:** implemented by Opus 4.8 (owner-approved deviation from the card's Sonnet 4.6);
  independent review by a different lineage (Codex/`spec-reviewer`) per CLAUDE.md 9.

## Contract facts established before coding

- `GET /practice/sessions/{id}` (`PracticeSession`) returns `coach_id` (UUID) but no
  coach display name and no `scenario_label`. The screen resolves the coach name from the
  coaches list (server page fetches `listCoaches`, passes as a prop — same pattern as
  `welcome/coach/page.tsx`). The mock's decorative `scenario_label` is dropped; the opening
  coach turn text is the scenario line, per s12.md.
- Turn response: `{ turn_consumed, retry_prompt?, user_turn?, coach_turn?, feedback?,
  session_status: active|completed }`. `feedback` is `TurnFeedback`
  (`show_feedback`, `natural_alternative?`, `korean_comment?`).
- `PracticeTurn.tts_audio_url` is nullable and is null in the current backend (TTS is #30).
  UI must tolerate null (text-only) — do not block on audio.
- Start requires `Idempotency-Key` and enforces a 2/day cap → 429 (s12.md US1 AC3).
- **No abandon endpoint exists** in `openapi.yaml`. So exit (US3 AC2) cannot set
  `status='abandoned'` server-side; the exit modal routes to `/home` client-side only
  (documented gap, unchanged from current behavior). Coach switch (US3 AC3) starts a new
  session (`POST` with the new `coach_id` on the loaded `expression_id`); the old session
  is not abandoned server-side (same gap). Both call out the missing endpoint.

## Design

### BFF + lib (server-only, mints internal-auth token, proxies to Spring)

- `src/lib/practice/start-session.ts` — `POST /api/v1/practice/sessions`
  `{expression_id, coach_id?}` + `Idempotency-Key`. Returns
  `{id, status, planned_turns, coach_id, opening_turn}`. `StartSessionError` exposes
  `isRateLimited` (429) and `isNotFound` (404 expression).
- `src/lib/practice/get-session.ts` — `GET /api/v1/practice/sessions/{id}`. Returns the
  session + ordered `turns`. `SessionNotFoundError` on 404.
- `src/lib/practice/submit-turn.ts` — `POST /api/v1/practice/sessions/{id}/turns`
  (JSON `text_content`) + `Idempotency-Key`. Returns the turn result. `SubmitTurnError`
  with status.
- Shared response types in `src/lib/practice/types.ts`.

### BFF routes (auth + Origin CSRF check, mirror existing routes)

- `src/app/api/practice/sessions/route.ts` — `POST` (start). Validates `expression_id`
  (non-empty string), maps 429 → `rate_limit_exceeded`, 404 → `not_found`.
- `src/app/api/practice/sessions/[session_id]/route.ts` — `GET` (fetch). 404 → `not_found`.
- `src/app/api/practice/sessions/[session_id]/turns/route.ts` — `POST` (submit). Validates
  `text_content` (non-empty string; audio path out of scope). 404 → `not_found`.

### Client

- `useRoleplaySession(sessionId)` — GET on mount; `loading | loaded | notFound | error`;
  `retry`. Mirrors `useExpressionDetail`.
- `RoleplayExperience` rewrite — props `{ sessionId, coaches }`. Renders from real session:
  coach name from `coach_id`→coaches map; progress `userTurnCount+1 / planned_turns`;
  history from `turns`. `sendTurn(text)` → POST turns with a per-turn `Idempotency-Key`;
  on `turn_consumed=false` show `retry_prompt`, do not append/advance; on success append
  `user_turn`, feedback card (if `show_feedback`), `coach_turn`, autoplay `tts_audio_url`
  if present; on `session_status=completed` route to `/practice/{id}/result` after the
  completion indicator. Coach switch → start new session, route to new id (429/error →
  inline notice). Exit → `/home`.
- `page.tsx` (server) — `auth()`, `listCoaches(userId)`, pass real `session_id` + coaches;
  redirect `/login` if unauthenticated.
- `ExpressionDetailExperience` (S09) — practice button becomes an async `POST` to
  `/api/practice/sessions`; pending state; 429 → daily-limit notice; else route to
  `/practice/{real_id}`.
- `BottomNav` — roleplay tab no longer points at `MOCK_SESSION_ID` (would 404 on real GET).
  Retarget to `/library` (choose an expression to practice). Full empty-state gate
  (s12.md US1 AC4) stays TBD.
- `mock-api.ts` — S12 `mockSession`/`mockTurnScript` marked reference-only; remove the
  live imports from the wired components. Keep types.

## Files expected to change / add

Add: `src/lib/practice/{types,start-session,get-session,submit-turn}.ts` (+ `.test.ts`),
`src/app/api/practice/sessions/route.ts`,
`src/app/api/practice/sessions/[session_id]/route.ts`,
`src/app/api/practice/sessions/[session_id]/turns/route.ts`,
`src/app/(app)/practice/[session_id]/useRoleplaySession.ts`.

Change: `src/app/(app)/practice/[session_id]/RoleplayExperience.tsx`, `.../page.tsx`,
`src/app/(app)/expression/[expression_id]/ExpressionDetailExperience.tsx`,
`src/components/app/BottomNav.tsx`, `src/lib/mock-api.ts`.

## Verification

- `bun test src/lib/practice` (new lib unit tests, fetcher-injected — no live calls).
- `bun run lint` and `bun run typecheck`.
- `/ui-verify` on `/practice/{id}` (render, waiting/processing/feedback/complete states,
  no console errors) with the backend mocked or a seeded session.
- Independent review by a different lineage → `docs/reviews/` (roleplay-state gate,
  CLAUDE.md 9 + quality-gates.md).

## Risks / review notes

- Roleplay-state screen: needs independent (non-Opus) review before merge.
- No abandon endpoint → exit/coach-switch server semantics are incomplete by contract, not
  by omission. Do not claim US3 is fully wired.
- Voice capture deferred: do not claim S12 US2 voice path is done.
- No secrets/migration/live-provider calls in this ticket; tests must inject fetchers.

## Final outcome

Executed 2026-07-14. Added `src/lib/practice/{types,start-session,get-session,submit-turn}.ts`
(+ tests, 14 pass), the three `src/app/api/practice/**` BFF routes, `useRoleplaySession`,
and rewrote `RoleplayExperience`/`page.tsx`; wired the S09 practice button to real
`POST /api/practice/sessions` and retargeted the `BottomNav` roleplay tab. Gates:
`bun test` (105 pass), `typecheck`, `lint`, `next build` all green.

Independent spec review (`docs/reviews/2026-07-14-ticket-61-s12-roleplay-real-data-review.md`)
found and fixed 4 issues before merge readiness:
- stable `Idempotency-Key` threaded client→BFF→lib (was regenerated per call — dedup
  guarantee restored for both start and turn submit);
- backend 409 (session not active) now routed to `/result` instead of a generic retry loop;
- opening-turn TTS autoplay on load (US1 AC2);
- ref-based double-submit guard.

Accepted v1 gaps (need backend follow-ups): feedback content not returned on GET (refresh
drops feedback cards), no abandon endpoint (exit/coach-switch not server-abandoned; coach
switch can consume both daily slots), 429 message lacks reset time, voice capture deferred.

`/ui-verify`: the S12 screen requires an authenticated session + a live backend + a seeded
practice session, none available in this local environment without owner-approved stack
bring-up. Static verification (build compiles all routes/pages; tests; typecheck; lint)
passed; the live browser proof is deferred to a run with the stack up.

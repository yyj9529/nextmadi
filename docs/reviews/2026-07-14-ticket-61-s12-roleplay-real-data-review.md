# Review: S12 roleplay screen real-data wiring (#61)

- **Ticket:** #61 — S12 Guided Roleplay screen (wire to real session/turn pipeline).
- **Implementer:** Opus 4.8 (owner-approved deviation from the card's Sonnet 4.6).
- **Reviewer:** independent spec-compliance reviewer, separate context (read-only).
- **Exec-plan:** `docs/exec-plans/2026-07-14-ticket-61-s12-roleplay-real-data.md`.
- **Gate:** roleplay-state (CLAUDE.md 9, `docs/quality-gates.md`). Two-gate: spec
  compliance, then code quality.

## Scope reviewed

Frontend/BFF wiring only (no backend/provider change). New `src/lib/practice/*`,
`src/app/api/practice/**`, `useRoleplaySession`; rewritten `RoleplayExperience`; S09
practice button → real `POST`; `BottomNav` retarget; S12 mock marked reference-only.
Checked against `docs/screens/s12.md`, `docs/api/openapi.yaml`, and the backend
`practice/dto/*` + `PracticeTurnService`/`PracticeController`.

## Passes

- **Contract fidelity:** all snake_case fields match the backend DTOs (`text_content`,
  `tts_audio_url`, `stt_confidence`, `feedback_shown`, `turn_consumed`, `retry_prompt`,
  `session_status`, `show_feedback`, `natural_alternative`, `korean_comment`,
  `planned_turns`, `coach_id`, `opening_turn`, `expression_id`). Nullable handling
  (tts null, feedback null, user/coach turn null on not-consumed) is correct.
- **Security:** internal-auth token minted server-side only (`server-only` on all libs);
  Origin/CSRF check on both state-changing routes; `auth()` gate on all routes + page
  `/login` redirect; no raw `text_content`/token/session-id logging.

## Findings and resolutions

| # | Severity | Finding | Resolution |
|---|----------|---------|------------|
| 1 | Blocker | `Idempotency-Key` regenerated per call (start + turn), defeating dedup → duplicate turns / double-spent daily cap on a retried network blip. | **Fixed.** Client mints a stable key per logical action, held in a ref across retries and cleared only on a definitive server response; BFF routes forward the client `Idempotency-Key` header to the lib. |
| 2 | Blocker | Backend 409 (session not active) mapped to generic 502 → user retries an unrecoverable session forever. | **Fixed.** `SubmitTurnError.isConflict`; turns route returns 409 `session_not_active`; client routes to `/result` on 409. |
| 3 | Partial AC | US1 AC2 "TTS auto-plays on render" only fired after a turn submit, not on load/restore. | **Fixed.** Mount effect autoplays the last coach turn's `tts_audio_url` (no-op while TTS is null per #30, but structurally correct). |
| 7 | Minor | Double-submit guard read a stale-closure `inputDisabled`. | **Fixed.** Ref-based in-flight guard (`sendingRef`). |

## Known gaps (accepted for v1 — owner's merge call)

- **#4 Feedback cards lost on refresh (US2 AC3 restore).** `GET` returns `feedback_shown`
  (bool) but not the feedback content; the frontend behaves correctly against the
  contract — restored history shows bubbles without the feedback card. Fixing needs a
  backend contract change (return feedback content on GET) — out of this ticket.
- **#5 Coach switch / exit not server-abandoned.** No abandon endpoint in `openapi.yaml`.
  Exit routes to `/home` client-side only; coach switch starts a new session while the
  old `active` session lingers — so "start then switch coach once" consumes both daily
  slots. Real UX consequence of the missing endpoint; needs a backend `abandon`/PATCH
  endpoint to resolve.
- **#6 429 message lacks reset time (US1 AC3).** The `Error` schema carries no reset-time
  field; "내일 다시 만나요" is the proxy. Contract gap, not a frontend defect.
- **Voice capture** (S12 US2 voice path) deferred by scope — text input is the live path;
  the mic button is a disabled placeholder.

## Verdict

Spec-compliant after the four fixes above; field shapes match the backend. Merge-ready
for the wired scope, with the four known gaps logged for the owner. Recommend the owner
also run the independent Codex pass per CLAUDE.md 9 before merge, and open follow-ups for
the abandon endpoint and GET-feedback-content.

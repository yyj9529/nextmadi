# Exec plan: S03b coach selection persistence (#53)

Status: **In progress.** Grounded in GitHub issue #53, `docs/screens/s03b.md`,
`docs/api/openapi.yaml`, `docs/data-model.md`, and the current checkout.

## Goal

Issue #53 (S03b) was reclassified PARTIAL on 2026-07-06: the coach-selection UI renders
off `mockCoaches` but never persists the choice. Remaining work: persist the selection
via `PATCH /me { selected_coach_id, is_onboarded: true }` (s03b.md AC2).

Owner decision (2026-07-06): wire the **real** `GET /coaches` too, so the cards carry
real UUID coach ids and the PATCH succeeds end-to-end. Sending a `mockCoaches` id
(`mock-coach-david`, not a UUID) to the backend would 400, so a mock-only PATCH would
not actually persist.

## Source specs

- GitHub issue #53: S03b coach selection, remaining = persist via `PATCH /me`.
- `docs/screens/s03b.md`: AC1 (3 cards from `GET /coaches`), AC2 (`PATCH /me` on
  select → route to `/home` or resume pending save), UI states (Loading / Loaded /
  Selecting / Selection error).
- `docs/api/openapi.yaml`: `GET /coaches` (envelope `{ coaches: [...] }`, `Coach`),
  `PATCH /me` (body `{ display_name?, selected_coach_id?, is_onboarded? }` → `User`).
- Backend (already merged, #52): `CoachController` `GET /api/v1/coaches`,
  `MeController` `PATCH /api/v1/me`. Both sit behind `InternalAuthFilter` — the BFF must
  mint an `X-Internal-Auth` token (user_id) exactly like `saveExpression` / `submitRating`.

## Key facts established before coding

- The frontend has **no** coaches or `/me` BFF wiring yet; `CoachCards` imports
  `mockCoaches` directly and `CoachSelectExperience` routes without any network call.
- Backend `CoachResponse` matches the frontend `Coach` type field-for-field
  (`id, slug, display_name, persona_summary, tts_voice_id`). The sample greeting line is
  frontend-only (`mockCoachSampleLines`, keyed by `slug`) and keeps working with real
  coaches because the backend returns `slug`.
- There is **no** NextAuth middleware guard. The only onboarding redirect lives in
  `auth/complete/page.tsx`. So a stale JWT `isOnboarded=false` after PATCH does **not**
  bounce the user off `/home`; the JWT refreshes `isOnboarded` from the DB on next login.
  Refreshing the live session token is out of scope for #53 (would touch the auth jwt
  callback) and is not required for correct routing here.

## Plan

New (all follow the existing server-only BFF pattern — mint internal token, call
`/api/v1`, typed error class):

1. `src/lib/coach/list-coaches.ts` — `GET /api/v1/coaches`, returns `Coach[]`.
2. `src/lib/user/patch-me.ts` — `PATCH /api/v1/me`, returns the updated `User`.
3. `src/app/api/me/route.ts` — `PATCH` BFF handler: same-origin + session check, body
   validation, proxy to `patchMe`. Mirrors the review-submit route handler.

Edits:

4. `src/app/(app)/welcome/coach/page.tsx` — server component: `auth()` → fetch coaches
   via `listCoaches` → pass `coaches` to the client Experience. On fetch failure render
   an error fallback (edge case: coaches not seeded).
5. `src/app/(app)/welcome/coach/CoachSelectExperience.tsx` — accept `coaches`; on
   continue `PATCH /api/me { selected_coach_id, is_onboarded: true }`; status
   `idle | saving | error`; on success route via `resolvePostOnboardingDestination`
   (honors pending save, AC2); on failure show retry toast, re-enable cards.
6. `src/components/app/CoachCards.tsx` — accept optional `coaches` (default
   `mockCoaches`, so S11 is untouched) + `disabled` / `savingId` for the Selecting
   visual. Non-breaking for the S11 settings modal.
7. `src/app/(app)/app.css` — Selecting (dim others, spinner on selected) + error toast.

## Tests

- Unit: `list-coaches` and `patch-me` (success, backend error → typed error, missing
  env). Route handler: 401 unauthenticated, 403 cross-origin, 400 bad body, 200 happy.
- Existing `pnpm typecheck` / `lint` / vitest suite stays green.

## Out of scope

- Wiring S11 settings to real coaches / real `PATCH /me` (separate ticket).
- Live NextAuth session-token refresh of `isOnboarded`.
- AC4 (already-onboarded revisit auto-redirect) — no middleware guard exists yet.

## Review

Independent spec-compliance review (spec-reviewer, different perspective from the
implementer) per CLAUDE.md workflow 9; verdict recorded in `docs/reviews/`.

# Review - S03b Coach Selection Persistence (#53)

- **Date:** 2026-07-06
- **Reviewer:** spec-reviewer (independent; implementer was a different agent)
- **Scope:** working-tree diff for #53 (BFF wiring of `GET /coaches` + `PATCH /me`)
- **Target:** GitHub issue #53, `docs/screens/s03b.md`, `docs/api/openapi.yaml`,
  backend `#52` contracts
- **Verdict:** **PASS-WITH-NITS.** All acceptance criteria in scope are met, the BFF
  pattern matches the existing siblings field-for-field, the shared `CoachCards` change
  is backward-compatible with S11, and the owner-flagged mock-id -> UUID 400 problem is
  resolved end-to-end. Open items are documentation-scope (AC3/AC4 not in this ticket)
  and one UI-state nit (no Loading skeleton).

---

## Gate 1 - Spec Compliance

| Requirement (s03b.md / openapi) | Result | Evidence |
|---|---|---|
| AC1: `GET /coaches` returns 3 coach profiles, each rendered as a card | PASS | `page.tsx:25` server-fetches via `listCoaches`; `CoachSelectExperience` -> `CoachCardList` renders name + sub-label + persona + sample line. |
| AC1: card shows name/sub-label, persona summary, sample greeting | PASS | `CoachCards.tsx:48-56` renders `display_name`, `persona_summary`, `mockCoachSampleLines[slug]`. Backend returns `slug` so sample line keeps working with real coaches. |
| AC1: voice-sample button (optional, TBD before W4) | PASS (deferred) | Not implemented; spec marks it optional/TBD. Correct not to invent. |
| AC2: tapping select calls `PATCH /me { selected_coach_id, is_onboarded: true }` | PASS | `CoachSelectExperience.tsx:42-49` PATCHes `/api/me` with exactly those two fields. |
| AC2: on success route to `/home` or resume pending save | PASS | `CoachSelectExperience.tsx:60` uses `resolvePostOnboardingDestination(readPendingSave())` -> `/home` or `/save/result/{id}` per #42 contract. |
| `GET /coaches` envelope `{ coaches: [...] }` | PASS | `list-coaches.ts:73-74` reads `body.coaches ?? []`; matches openapi + `CoachListResponse`. |
| `Coach` schema fields (`id, slug, display_name, persona_summary, tts_voice_id`) | PASS | `CoachResponse.java` matches frontend `Coach` type field-for-field. |
| `PATCH /me` body `{ display_name?, selected_coach_id?, is_onboarded? }` -> `User` | PASS | `patch-me.ts:69-78` sends only present fields; `PatchMeResult` mirrors `UserResponse`. |
| UI state: Selecting (selected highlighted, others dimmed, spinner on selected) | PASS | `CoachCards.tsx` `is-busy`/`savingId` + `app.css:1334` dims `:not(.is-saving)`, spinner on saving card. |
| UI state: Selection error (toast + retry, cards interactive again) | PASS | `CoachSelectExperience.tsx:101-105` toast `role="alert"`; on error `saving=false` re-enables cards; user can re-select and retry. |
| UI state: Loaded (3 cards + intro copy) | PASS | Header greeting + `CoachCardList`. |
| UI state: Loading (3 card skeletons) | NIT | Coaches are fetched server-side in `page.tsx` with no `loading.tsx`/Suspense boundary, so there is no skeleton frame. See P2-1. |
| Edge: coaches not seeded / fetch fails -> cannot onboard | PASS | `page.tsx:26-34` renders `CoachSelectUnavailable` on throw or empty list. |
| Edge: force-quit during PATCH leaves `is_onboarded=false` | PASS (by design) | No local optimistic flip; `is_onboarded` only advances on backend 200. |
| AC3 (route guard blocks unselected users) | OUT OF SCOPE | No NextAuth middleware exists; exec-plan lists as out of scope. Owner-acknowledged. |
| AC4 (already-onboarded revisit auto-redirects to /home) | OUT OF SCOPE | Same; no guard yet. Documented in exec-plan "Out of scope". |

## Gate 1 - BFF pattern parity vs siblings

| Aspect | Result | Evidence |
|---|---|---|
| Internal-auth minting (`X-Internal-Auth` = user_id) | PASS | `list-coaches.ts:55` / `patch-me.ts:63` mirror `submit-rating.ts` / `save-expression.ts`. |
| Same-origin CSRF check on state change | PASS | `api/me/route.ts:29-47` is byte-identical in approach to `review/.../submit/route.ts`. |
| Session check -> 401 | PASS | `route.ts:49-53`. |
| Env-var handling + trailing-slash trim | PASS | Same `PHRASELOG_BACKEND_BASE_URL` / `INTERNAL_AUTH_SECRET` guards and `.replace(/\/+$/, "")`. |
| Error mapping (typed error class -> Response) | PASS | `PatchMeError` 400 -> 400, else 502; matches `SubmitRatingError` shape. Minor: backend 404/401 collapse to 502 (acceptable for an onboarding user that must exist). |

## Owner-flagged risk: mock-id -> UUID 400

**RESOLVED end-to-end.** Cards now carry real UUIDs from `GET /coaches`
(`page.tsx` -> `CoachSelectExperience` -> `selected.id`). The BFF route additionally
guards with `UUID_RE` before proxying (`route.ts:69-77`), and the backend
`UserService.validatedCoachId` verifies the coach exists. A mock id like
`mock-coach-david` is now impossible on the S03b path and is explicitly rejected with
400 by `route.test.ts:95-102`.

## Shared `CoachCards` change vs S11

**No regression.** New props (`coaches`, `disabled`, `savingId`) are all optional with
defaults (`coaches = mockCoaches`, `disabled = false`, `savingId = null`).
`SettingsExperience.tsx:204-206` calls `CoachCardList` with only `selectedId` + `onSelect`,
so S11 renders exactly as before off `mockCoaches`.

## Gate 2 - Code Quality (light pass)

| Aspect | Result | Evidence |
|---|---|---|
| No raw user text / PII persisted client-side | PASS | Only coach UUID + boolean flag cross the wire; user identified by session, never body. |
| `is_onboarded` irreversibility preserved | PASS | Route rejects `false` (`route.ts:79-85`); backend `validatedOnboarding` also forbids false. |
| Tests meaningful | PASS | 13 tests pass (`list-coaches`, `patch-me`, route: 401/403/400-non-uuid/400-false/400-empty/502/happy). |

## Findings

### P2 - Nit

| # | File | Issue | Confidence | Route |
|---|---|---|---|---|
| 1 | `src/app/(app)/welcome/coach/page.tsx` | s03b UI-states table lists a "Loading = 3 card skeletons" state. The server-side `await listCoaches` blocks first paint with no `loading.tsx`/Suspense, so no skeleton is shown during the fetch. Either add a route `loading.tsx` with 3 skeletons or update the spec table to note the Loaded-only server render. Not a blocker. | 70 | ui-state |

## Blockers

None.

## Questions for the owner

1. AC3/AC4 (onboarding route guard + already-onboarded auto-redirect) are deferred to a
   later ticket per the exec-plan. Confirm #53 is intended to close as "persistence
   only" with those tracked separately, so the s03b ACs are not marked fully done.
2. Live NextAuth session-token `isOnboarded` is not refreshed after PATCH (exec-plan
   out-of-scope). Routing is correct because destination is resolved client-side, but if
   any future middleware reads the stale JWT `isOnboarded=false`, it would bounce the
   user. Confirm this is acceptable until the auth-jwt-callback ticket lands.

## Verification Run

- `bun test src/lib/coach/list-coaches.test.ts src/lib/user/patch-me.test.ts src/app/api/me/route.test.ts`: 13 pass, 0 fail.

## Verdict

**PASS-WITH-NITS.** In-scope ACs (AC1, AC2), UI states (Loaded/Selecting/Error), edge
cases (unseeded/fetch-fail), BFF parity, contract field-matching, and the mock-id 400
fix are all correct. One UI-state nit (Loading skeleton) and two documentation-scope
questions (AC3/AC4 deferral, stale-JWT) remain for the owner's merge call.

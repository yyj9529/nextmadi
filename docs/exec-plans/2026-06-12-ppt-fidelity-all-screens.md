# Exec plan: PPT fidelity — all remaining screens

## Goal

Extend the merged S04/S07 PPT-fidelity direction to every remaining product
screen (S01, S02, S03, S03b, S05a, S06, S08, S09, S10, S11, S12, S12b) as
static/mock UI. Mobile layout follows `docs/design/reference/ppt/*.PNG`;
desktop (768px+) expands naturally like S04/S07. No real API calls; all
buttons clickable with deterministic mock routing.

## Source specs

- `CLAUDE.md`, `SECURITY.md`
- `docs/design/PPT_FIDELITY_SPEC.md`
- `docs/design/reference/ppt/s01..s12b.PNG` (mobile visual source of truth)
- `docs/screens/s01.md` … `s12b.md` (behavior, copy, states)
- `docs/api/openapi.yaml` (mock data shapes)

## Structure decisions

- All product screens move into the `(app)` route group so they share
  `app.css`, `.app-viewport`, `.app-screen`, and tokens. `(site)` keeps only
  `/terms` and `/privacy` placeholders.
- Each route keeps a server `page.tsx` (so `metadata` exports stay), with a
  colocated client component when the screen is interactive.
- One mock module `src/lib/mock-api.ts` holds typed mock data matching
  `openapi.yaml` schemas. Every block carries a comment naming the real
  method/path it stands in for, so future BFF wiring is mechanical.
- Stable mock IDs: `mock-analysis`, `mock-expression-1`, `mock-session-1`.
- Shared components extracted (no behavior change to S04/S07 beyond making
  previously dead buttons route per the interaction rule):
  - `src/components/app/icons.tsx` — SVG icons used across screens
  - `src/components/app/BottomNav.tsx` — S04/S08 bottom nav (all 4 tabs link)
  - `src/components/app/PlayButton.tsx` — client, simulates `POST /tts/playback`
  - `src/components/app/AnalysisModals.tsx` — S05a text-input sheet + S06
    loading modal; used by S04 (mic + text button) and S02 (submit)
  - `src/components/app/CoachCards.tsx` — coach comparison cards shared by
    S03b and the S11 coach-change modal

## Mock routing map

- S01 example card → `/try?example={id}` (pre-fills textarea)
- S02 분석 요청 → S06 modal → `/save/result/mock-analysis`
- S03 any provider / 가입 완료 → `/welcome/coach` (new-user flow)
- S03b 시작하기 → `/home` (simulates `PATCH /me`)
- S04 mic → S06 → result; 텍스트로 입력하기 → S05a → S06 → result
- S07 저장하기 → `/expression/mock-expression-1`; 다시 → `/home`
- S08 card → `/expression/{id}`; search filters mock list client-side
- S09 연습하기 → `/practice/mock-session-1`; 삭제 confirm → `/library`;
  queue toggle flips locally
- S10 ratings advance through 3 mock cards → completion → `/home`
- S11 로그아웃 / 계정 삭제 confirm → `/`; coach change via modal
- S12 send turn → scripted mock turns; final turn → `/practice/mock-session-1/result`
- S12b 저장 → "저장됨 ✓"; 홈으로 → `/home`; 원본 표현 보기 → `/expression/mock-expression-1`

## Desktop layout choices (768px+)

- S01: hero + CTA left, example cards right (two-column grid)
- S02, S10, S12: centered single column (~560–760px)
- S03, S03b: centered narrow auth column (~480px)
- S08: two-column card grid
- S09, S12b: two-column grids mirroring S07's main/side split
- S11: two-column (profile/coach left, usage/account right)

## Files expected to change

- Delete `(site)` placeholders for product routes; new pages + client
  components under `src/app/(app)/…`
- `src/app/(app)/app.css` — extended per screen
- `src/app/(app)/home/page.tsx`, `save/result/[id]/page.tsx` — switch to
  shared icons/nav, wire actions (visuals unchanged)
- `src/lib/mock-api.ts`, `src/components/app/*` — new
- `src/lib/routes.ts` — removed if no longer referenced

## Acceptance criteria

- 320/390px: no horizontal scroll; layouts match the PPT mobile mocks
- 768/1280px: natural wide layouts, no narrow phone column
- All buttons/links navigate per the mock routing map; no dead buttons
- Korean copy is natural and matches the PPT/specs (no mojibake)
- `bun run lint`, `bun run typecheck`, `bun run build` pass

## Test plan

- lint, typecheck, production build
- Browser checks at 320, 390, 768, 1280 via Playwright MCP if available
- Click-through of the full loop: S01 → S02 → S06 → S07 → S09 → S12 → S12b

## Risk areas

- Do not regress S04/S07 visuals while extracting shared pieces
- `(site)` → `(app)` route moves must not change public URLs
- No new dependencies; no real network calls

## Decision log

- All mock logins route to `/welcome/coach` (new-user path showcases S03b);
  S03b completion routes to `/home`
- S06 mock completes in ~2.4s with the 3 staged messages compressed; the
  10s cancel button is implemented but normally unreached
- S09 collapsed variants expand on tap (accordion), matching the PNG's
  first-expanded / rest-collapsed state

## Final outcome

Implemented. All 12 remaining product screens replaced their
`RoutePlaceholder` with PPT-fidelity static/mock UI inside the `(app)` route
group. `(site)` retains only `/terms` and `/privacy`. The full mock loop
(S01 → S02 → S06 → S07 → S09 → S12 → S12b, plus auth S03/S03b and
S08/S10/S11) routes end-to-end with the stable mock IDs. S04/S07 visuals are
unchanged; their dead buttons (mic, 텍스트로 입력하기, 저장하기, 다시,
재생) now route/simulate per the interaction rule.

## Verification

- `bun run lint`, `bun run typecheck`, `bun run build` — all pass
  (`.next` was cleared once to drop stale route types from deleted pages)
- Playwright (Chromium) checks:
  - 390px: S01, S02, S03, S03b, S04+S05a sheet, S07, S08, S09, S10, S12,
    S12b screenshots match the PPT references
  - 320px: no horizontal overflow on home/detail/roleplay/result/review
  - 768px: S08 two-column grid + static nav, S12 centered chat verified
  - 1280px: S01, S09, S11 desktop grids verified
- Interaction spot checks: S12 turn script (user bubble → feedback card →
  coach reply → progress 2/3), S10 reveal → 3 rating buttons

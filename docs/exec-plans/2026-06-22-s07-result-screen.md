# Exec plan: S07 Analysis Result Screen — real wiring (#40)

Status: **In progress.** Branch `feat/s07-result-screen`.

## Goal

Turn the S07 page from the PPT-fidelity mock pass into a real screen wired to the
backend, per `docs/screens/s07.md` (parent epic #44):

- Page entry fetches the analysis via `GET /api/v1/analysis/{id}` (internal auth:
  `user_id` when authenticated, anonymous `session_token` otherwise) and renders the
  Korean input + 3 variant cards (English, tone, IPA, Korean phonetic, pronunciation
  tip, cultural tip).
- Invalid / unowned id → 404 not-found state with CTA (`/home` authenticated, `/try`
  pre-signup) — US1-AC3.
- Each card play button calls `POST /api/tts/playback` (BFF) → plays the signed URL;
  repeat play exercises backend `cache_status='hit'`; failure → toast "음성 재생 실패,
  다시 시도해주세요", card stays usable — US2.
- Save state transition (저장하기 → 저장 중 → 저장됨 ✓ / 📚 토스트) — already delivered
  in #42; unchanged here.

## Owner decisions (2026-06-22)

1. **TTS scope:** build the BFF route + frontend wiring now, with graceful fallback,
   even though the TTS backend (`POST /tts/playback`, #30) is **not yet implemented**.
   Until #30 ships, the BFF call fails and the card shows the failure toast — which is a
   real spec state (US2-AC3). When #30 lands, the same contract works unchanged.

2. **Soft-delete revisit (the spec TBD):** option **(c) "복원하기" CTA**. Recorded in
   `s07.md`. Tapping "복원하기" un-deletes the original expression (preserves review
   history; no duplicate row). Full wiring needs the Save backend to signal the
   soft-deleted-restorable state (distinct from the existing 409 already-saved case) —
   that is a follow-up on the expression-save backend (#41 area), not in this frontend
   ticket. The current 409 → "저장됨 ✓" normalization stands until then.

## Scope boundary

In scope: `getAnalysis` server client + page real fetch + 404 + loading skeleton; TTS
BFF route + Spring TTS client + real `PlayButton` (fetch, HTML5 Audio, pause/resume,
failure toast); per-card pronunciation/cultural tip render; CSS for the new states;
unit tests for both new libs; spec decision record; openapi unchanged (contracts
already present).

Out of scope / not touched: the TTS backend (#30); coach-voice resolution (a default
voice is used server-side until the coach API is wired — clearly marked TODO); the
"복원하기" CTA full wiring (backend signal first); Save flow (#42, unchanged); any DB
migration; internal-auth / JWS behavior.

## Design

- **`src/lib/analysis/get-analysis.ts`** (server-only) mirrors `save-expression.ts`:
  mints `X-Internal-Auth` with exactly one of `user_id` / `session_token` (XOR
  invariant), GETs the analysis, throws `AnalysisNotFoundError` on 404.
- **`src/lib/tts/playback.ts`** (server-only) posts `{ text, voice_id }` to Spring,
  throws `TtsPlaybackError(status)` on non-2xx.
- **`src/app/api/tts/playback/route.ts`** (BFF) does the same-origin/CSRF check and
  session resolution as the expressions route, resolves `voice_id` **server-side**
  (client-supplied voice is not trusted; consistent voice keeps the backend cache
  stable), and normalizes any failure to a 502 + toast copy. No raw text/token logging.
- **`PlayButton`** fetches on play-from-stopped, caches the `Audio` element for
  pause/resume, shows the failure toast on error, swaps to the pause icon while playing.
- **`page.tsx`** resolves auth/anon, fetches, renders real cards, and falls back to the
  not-found view on `AnalysisNotFoundError` (and, defensively, any fetch failure since
  the user cannot distinguish ownership). `loading.tsx` provides the shimmer skeleton.

## Verification

- Unit: `bun test src/lib/analysis src/lib/tts` (9 pass). Full suite 38 pass.
- Typecheck clean (`tsc --noEmit`), ESLint clean on changed files, `next build` OK.
- Browser, against a **live Spring backend (`phraselog_dev`) + Next dev server**
  (2026-06-23). A real `analysis_requests` row was inserted directly into the DB
  (anon-owned, `session_token=verify-anon-s07-0001`) to bypass the Anthropic pipeline,
  and the browser carried a matching `phraselog_anon_session` cookie:
  - ✅ **Loaded 3-card** — real `GET /analysis/{id}` via BFF internal-auth; original
    Korean input + 3 ordered variant cards (tone, IPA, Korean phonetic, **pronunciation
    tip + cultural tip** — the fields the mock pass omitted), per-card play button, back
    link → `/try`. Console clean (0 err / 0 warn). [`s07-loaded-desktop.png`]
  - ✅ **404 not-found** — unowned id → backend real 404 → "찾을 수 없는 결과예요" +
    "다시 시도하기" → `/try`. [`s07-notfound-live404.png`]
  - ✅ **TTS graceful fallback** — play → `POST /api/tts/playback` → 502 (backend has no
    `/tts/playback` yet) → toast "음성 재생 실패, 다시 시도해주세요", card stays usable
    (US2-AC3). The 502 + its browser console line are the expected fallback signature,
    not a bug. [`s07-tts-fallback-toast.png`]
  - ✅ **Pre-signup save** — 저장하기 → `pending_save`
    `{analysis_request_id, selected_variant_order:1}` written to sessionStorage → routed
    to `/login` (US3-AC3).
  - ⚠️ **Authenticated save 저장됨 ✓ transition** — NOT browser-verified here; needs an
    interactive OAuth login. The `/api/expressions` BFF + ResultActions transition are
    unchanged from #42 (verified there).
  - ⚠️ **TTS real cache-hit (`cache_status='hit'`)** — gated on #30 + `OPENAI_API_KEY`
    (absent from `.env`) + S3. The wiring is proven up to the BFF boundary.
  - Screenshots in `.playwright-mcp/` (gitignored).

  Local run notes: backend started on the default profile with
  `SPRING_DATASOURCE_*` + `PHRASELOG_FLYWAY_ENABLED=false` (schema already present in
  `phraselog_dev`) + `PHRASELOG_INTERNAL_AUTH_SECRETS` aligned to the Next `.env`
  `INTERNAL_AUTH_SECRET`. `coach_profiles` is currently empty in `phraselog_dev`, which
  did not affect this flow (the TTS BFF resolves a default voice).

## Follow-ups

- #30 TTS backend → removes the fallback-only limitation; enables cache-hit evidence.
- Coach-voice resolution in the TTS BFF (replace the default voice) once the coach API
  is reachable server-side.
- Save backend soft-deleted-restorable signal → enables the "복원하기" CTA (decision c).

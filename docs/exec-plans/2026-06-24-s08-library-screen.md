# Exec plan: S08 Library Screen — real wiring (#46)

Status: **Implemented, Codex follow-up in progress.** Branch `feat/s08-library-screen`.
Epic E07.2. Codex follow-up closes review gaps: page-level auth guard, stale
pagination response guard, dependency-free fixed-row virtualization, and source-doc
sync for the 500+ item case.

## Goal

Turn the S08 `/library` page from the mock pass into a real screen wired to the
backend, per `docs/screens/s08.md` (ADR-002 cumulative bookshelf):

- Page lists the user's saved expressions via `GET /expressions?limit=20`, sorted
  `created_at` DESC. Each card: Korean `original_situation` (~80 char truncate),
  selected variant `english_text` (~80 char), `tone_label` badge, relative time.
- Infinite scroll: IntersectionObserver sentinel → `GET /expressions?limit=20&cursor={next_cursor}`,
  appends below existing cards, bottom spinner during fetch (US1-AC2). Stale
  pagination responses are ignored when the active search generation changes.
- Keyword search: 300ms debounce → `GET /expressions?q={keyword}`; URL query synced
  (`/library?q=keyword`) so back-nav from S09 restores search state (US2, edge case).
- Card tap → `/expression/{id}` (S09). Already wired via `<Link>`.
- All UI states from `s08.md`: skeletons, empty library (home CTA), search no-results
  (clear CTA), pagination spinner, end-of-list, initial-load full-screen retry,
  pagination inline retry (existing cards retained).
- Header bookshelf count `📚 N권`.
- 500+ item lists use dependency-free fixed-row windowing; no `react-window` or
  other dependency added.

## Owner decisions (2026-06-24)

1. **Virtualization (the spec TBD):** **resolved during Codex follow-up.** v1 ships
   cursor pagination plus dependency-free fixed-row windowing. No
   `react-window`/`@tanstack/virtual`, zero new deps. The source spec and decision
   backlog now record this implementation choice.

2. **Header bookshelf count:** **reuse the dashboard API.** `GET /expressions` has no
   `total`; instead read `bookshelf_count` from `GET /home/dashboard` (backend
   `HomeController`/`DashboardResponse`, #95 — already implemented). One extra read on
   page entry. No backend or OpenAPI change. (Alternatives rejected: adding `total` to
   the list response touches backend + spec — a review-gated change for a header
   number; dropping the count diverges from the `📚 47권` spec.)

## Scope boundary

In scope: BFF `GET` handler on `src/app/api/expressions/route.ts`; `list-expressions.ts`
server client; dashboard count BFF read + server client; `useLibrary` client hook
(debounce + cursor append + error states + stale response guard); `LibraryExperience.tsx`
real wiring + URL-query sync + IntersectionObserver + fixed-row windowing; all `s08.md`
UI states + CSS; unit tests for the new server libs and Codex follow-up helpers;
browser verification evidence when tooling is available.

Out of scope / not touched: adding `total` to the list response; soft-delete real-time
invalidation across tabs (v1 accepts stale, per `s08.md` edge case); the S09 detail
screen itself; any DB migration; internal-auth / JWS behavior; backend list/search
(already complete).

## Design

- **`src/lib/expression/list-expressions.ts`** (server-only) mirrors
  `save-expression.ts`: mints `X-Internal-Auth` with the user's id, GETs Spring
  `/api/v1/expressions` with `q`/`cursor`/`limit`, returns `{ items, next_cursor }`,
  throws `ListExpressionsError(status)` on non-2xx. No raw text/token logging.
- **BFF `GET` on `src/app/api/expressions/route.ts`**: `auth()` → `userId` (401 if
  none); no CSRF/same-origin check needed for a read; parse `q`/`cursor`/`limit` from
  the URL; delegate to `list-expressions.ts`; normalize backend/network failure to 502.
  (The existing `POST` handler is unchanged.)
- **Bookshelf count read**: a small server client `get-bookshelf-count.ts` (or reuse a
  dashboard client if introduced) hitting `GET /home/dashboard`, projecting
  `bookshelf_count`. Surfaced through the same BFF auth pattern. Count failure degrades
  gracefully — header hides the number rather than erroring the whole page.
- **`src/app/(app)/library/useLibrary.ts`** (client hook): state `items`, `nextCursor`,
  `loadingInitial`, `loadingMore`, `errorInitial`, `errorMore`, `keyword`. 300ms
  debounce on keyword → reset list + refetch. `loadMore()` appends by `nextCursor`,
  dedupes by id. Plain `useState` + `fetch` — no SWR/react-query (dep policy).
- **`LibraryExperience.tsx`**: drop `mockLibraryItems`; consume `useLibrary`; sync
  `keyword` ↔ `?q=` via `useSearchParams` + `router.replace`; IntersectionObserver
  sentinel at list end → `loadMore` (skip when `nextCursor` is null). Render every
  `s08.md` state.

## Verification (gates)

- `/spec-check` — diff vs `s08.md` G-W-T (US1 list/pagination/empty, US2 search/clear).
- `ui-verify` skill — browser evidence: initial render, empty library, search +
  no-results, pagination append, console clean. **AC-required.**
- Unit tests for `list-expressions.ts` (param passthrough, error mapping) and the count
  client.
- `docs/quality-gates.md` frontend gate + two-gate review (spec compliance, then code
  quality). Reviewer must be a different agent than the implementer (ADR handoff).

## Open questions

None blocking. Relative-time formatting util (e.g. "3분 전") — reuse if one exists
under `src/lib`, else add a small local helper; not worth a dependency.

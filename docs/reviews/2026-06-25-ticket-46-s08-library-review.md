# Review - S08 Library Screen (#46)

- **Date:** 2026-06-25
- **Reviewer:** Codex
- **Scope:** working tree on `feat/s08-library-screen`
- **Target:** GitHub issue #46, `E07.2 S08 Library Screen`
- **PR state:** no PR found via `gh pr list --state all --search "46"`
- **Verdict:** **Code/spec follow-up PASS; authenticated browser-state QA still needs
  real session/data evidence before closing the GitHub issue.** The earlier review
  gaps are fixed: `/library` now redirects anonymous users to `/login`, pagination
  ignores stale responses after search changes, and the 500+ item case is handled with
  dependency-free fixed-row windowing.

---

## Gate 1 - Spec Compliance

| Criterion | Result | Evidence |
|---|---|---|
| `GET /expressions?limit=20` first batch | PASS | `useLibrary` sends `limit=20`; BFF proxies `q`/`cursor`/`limit` to Spring. |
| Newest-first cards with selected variant text, tone, relative time | PASS | Backend list query orders `created_at DESC, id DESC`; `LibraryExperience` renders selected variant text, tone badge, and relative time. |
| Infinite scroll cursor pagination | PASS | IntersectionObserver sentinel calls `loadMore`; new pages append and dedupe by id. |
| Stale pagination response guard | PASS | `requestGenerationRef` + `isCurrentLibraryRequest` prevent old page responses from mutating the current search results. |
| Card tap to S09 detail | PASS | Cards link to `/expression/{item.id}`. |
| Empty library and no-results states | PASS | Separate empty-library CTA to `/home` and search no-results clear action exist. |
| 300ms debounced search and URL query restore | PASS | Debounce, URL sync, and `initialQuery` resync are implemented. |
| Initial and pagination error states | PASS | Initial error shows full retry; pagination error keeps existing cards and shows inline retry. |
| Header bookshelf count | PASS | `/api/expressions/count` reuses backend `/api/v1/home/dashboard` `bookshelf_count`. |
| Authentication required | PASS | `LibraryPage` calls `requireAuthenticatedUserId`; HTTP check returned `307 location: /login` for anonymous `/library`. |
| 500+ item virtualization | PASS | `getVirtualWindow` + `library-virtual-viewport` render a fixed-row window without a new dependency; `docs/screens/s08.md` and `docs/DECISION_BACKLOG.md` now record the decision. |
| Browser verification evidence | PARTIAL | HTTP-level redirect was verified locally. Full authenticated empty/search/pagination visual QA was not automated because `agent-browser` is not installed and no test auth fixture/session was available. |

## Gate 2 - Code Quality

| Criterion | Result | Evidence |
|---|---|---|
| Auth boundary is explicit | PASS | `requireAuthenticatedUserId` has direct tests for user id return and anonymous redirect. |
| BFF pattern preserved | PASS | Next route handlers call `auth()` and mint `X-Internal-Auth` only server-side. |
| Backend contract compatibility | PASS | Focused Spring expression/home tests passed. |
| Tests are meaningful | PASS | New tests cover auth guard helper, stale request generation, virtual window math, list client, and bookshelf count client. |
| No DB migration risk | PASS | No migration in this change. |
| No secret/raw user text exposure | PASS | Reviewed files do not log search text, user text, or tokens. |
| Formatting and build | PASS | `bun test`, `bun run typecheck`, `bun run lint`, `bun run build`, backend focused tests, and `git diff --check` passed. |

## Resolved Findings

| # | Original Issue | Resolution |
|---|---|---|
| 1 | `Authentication: required` was not enforced at page entry. | Added `requireAuthenticatedUserId()` and called it from `/library` page; anonymous HTTP request redirects to `/login`. |
| 2 | Stale pagination responses could append old-query items after search changes. | Added request generation tracking and pagination abort/reset on initial loads. |
| 3 | 500+ item virtualization was missing and source docs still said TBD. | Added dependency-free fixed-row windowing plus unit tests; updated `docs/screens/s08.md`, `docs/DECISION_BACKLOG.md`, and the exec plan. |

## Verification Run

- `gh issue view 46 --json ...`: issue is OPEN; requirements include virtualization and browser verification evidence.
- `gh pr list --state all --search "46" --json ...`: returned no PRs.
- `bun test src/lib/auth/require-authenticated-user.test.ts src/lib/library-request-generation.test.ts src/lib/virtual-window.test.ts src/lib/expression/list-expressions.test.ts src/lib/expression/get-bookshelf-count.test.ts`: 13 passed.
- `bun run typecheck`: passed.
- `bun run lint`: passed.
- `bun run build`: passed; `/library` is now dynamic due the server auth guard.
- `backend/.\\gradlew.bat test --tests "com.phraselog.expression.*" --tests "com.phraselog.home.*"`: `BUILD SUCCESSFUL`.
- `curl.exe -I --max-time 10 http://127.0.0.1:3000/library`: `307 Temporary Redirect`, `location: /login`.
- `git diff --check`: passed with only expected LF/CRLF warnings.

## Close Recommendation

The code/spec blockers found in review are resolved. Before closing #46, attach one
authenticated browser QA note or screenshot set for the user-visible states: empty
library, search no-results + clear, pagination append, and pagination retry. The local
server is available at `http://localhost:3000` in this session.

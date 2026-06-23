# Review - Home Dashboard Aggregation (#54)

- **Date:** 2026-06-24
- **Reviewer:** Codex
- **Scope:** working tree on `feat/s04-home-dashboard`
- **Target:** GitHub issue #54, `E09.3 GET /home/dashboard Aggregation API`
- **PR state:** no PR found for `feat/s04-home-dashboard` via `gh pr list --head`
- **Verdict:** **PASS for the documented single HTTP round trip; contract caveat on
  "single query round-trip."** The backend endpoint, DTO, service aggregation, OpenAPI
  shape, service/controller tests, and full backend test suite are present and passing.
  If the issue's acceptance criterion means one DB query/one JDBC round trip, this
  implementation needs a follow-up dashboard-specific query before #54 is closed.

---

## Gate 1 - Spec Compliance

| Criterion | Result | Evidence |
|---|---|---|
| `GET /home/dashboard` exists under `/api/v1/home/dashboard` | PASS | `HomeController` maps `ApiPaths.V1 + "/home"` and `@GetMapping("/dashboard")`. |
| Response includes user profile and selected coach | PASS | `DashboardResponse` returns `user` and nullable `coach`; `HomeService` resolves `selected_coach_id` with fallback logging. |
| Response includes bookshelf count | PASS | `ExpressionRepository.countActive(userId)` counts active expressions where `deleted_at IS NULL`. |
| Response includes 2 most recent compact expressions | PASS | `HomeService` calls `expressionRepository.list(..., limit = 2)` and the existing list query orders `created_at DESC, id DESC`. |
| Response includes due review count | PASS | `ReviewRepository.countDue(userId)` excludes removed cards and soft-deleted parent expressions. |
| Response includes today's roleplay count and daily limit 2 | PASS | `HomeService` uses the same UTC day boundary pattern as `UsageService` and returns `DAILY_ROLEPLAY_LIMIT = 2`. |
| Works for a new user with 0 data | PASS | `HomeServiceTests.newUserGetsZerosAndEmptyRecentList` covers zero counts and empty recent list. |
| Single first-paint API round trip | PASS | One backend endpoint returns the whole S04 first-paint payload. |
| Single DB query round trip, if intended by issue #54 | NEEDS DECISION | Current service performs a constant number of repository calls, not one combined SQL query. |

## Gate 2 - Code Quality

| Criterion | Result | Evidence |
|---|---|---|
| Auth boundary is preserved | PASS | Controller reads only verified `InternalAuthPrincipal`; anonymous/session principals are rejected in service tests. |
| Error contract follows project pattern | PASS | Uses `ApiErrorException` and `GlobalExceptionHandler`; 401 controller path is covered. |
| Tests are meaningful | PASS with Docker caveat | Home service/controller tests execute; JDBC repository tests exist but were skipped locally because Docker/Testcontainers were unavailable. |
| No migration risk | PASS | No DB migration in this change. |
| No secret/raw user text exposure | PASS | Reviewed files add no secrets and no raw user-content artifacts. |
| Formatting and whitespace | PASS | `spotlessCheck` and `git diff --check` passed. |

## Findings

### P1 - High

| # | File | Issue | Reviewer | Confidence | Route |
|---|---|---|---|---|---|
| 1 | `backend/src/main/java/com/phraselog/home/service/HomeService.java:67` | Conditional contract gap: #54 says "single query round-trip"; this implementation uses several constant-count repository calls. If that phrase means one DB/JDBC round trip, add a dashboard-specific aggregate query or update the ticket wording to "single API call with no N+1." | api-contract, performance | 75 | `manual -> downstream-resolver` |

## Contract Coverage Notes

- `coach` is nullable in OpenAPI to match the S04 fallback-greeting edge case.
- Time-of-day greeting stays client-side, as required by issue #54.
- The code is no-N+1 in the usual sense: query count does not grow with the number of
  expressions/review cards. It is not a single SQL query.
- The new `backend/src/main/java/com/phraselog/home/` and
  `backend/src/test/java/com/phraselog/home/` files are still untracked. They must be
  staged before commit/PR, otherwise the endpoint itself will not ship.

## Verification Run

- `gh issue view 54 --json ...`: issue is OPEN; requirements match `GET /home/dashboard`.
- `gh pr list --head feat/s04-home-dashboard --json ...`: returned `[]`.
- `.\gradlew.bat test --tests "com.phraselog.home.*" --tests "com.phraselog.PhraselogBackendApplicationTests"`: passed.
- `.\gradlew.bat test --tests "com.phraselog.coach.repository.JdbcCoachRepositoryTests" --tests "com.phraselog.expression.repository.JdbcExpressionRepositoryTests"`: Gradle passed, but the two Testcontainers suites were skipped locally.
- `.\gradlew.bat test`: passed.
- `.\gradlew.bat spotlessCheck`: passed.
- `git diff --check`: passed.

## Local Verification Caveat

- `JdbcExpressionRepositoryTests`: `tests="11" skipped="11"`.
- `JdbcCoachRepositoryTests`: `tests="2" skipped="2"`.
- `HomeServiceTests`: `tests="6" skipped="0"`.
- `HomeControllerTests`: `tests="2" skipped="0"`.

## Notes for the Owner

If #54's "single query round-trip" was intended as "one browser/BFF-to-backend call,"
the implementation is ready for commit after staging the untracked home files. If it
was intended as "one SQL/JDBC round trip," treat finding #1 as a blocker before closing
the ticket.

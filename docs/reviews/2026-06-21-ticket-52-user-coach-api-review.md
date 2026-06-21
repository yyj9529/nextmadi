# Review - User/Coach API (#52)

- **Date:** 2026-06-21
- **Reviewer:** Codex (independent review of Claude Code implementation)
- **Scope:** untracked backend files on `main`: `com.phraselog.coach`, `com.phraselog.user`,
  `V003__seed_coaches.sql`, and related tests.
- **Context read:** GitHub issue #52, `docs/api/openapi.yaml`, `docs/screens/s03b.md`,
  `docs/screens/s11.md`, `docs/data-model.md`, `docs/auth.md`, and `docs/quality-gates.md`.
- **Verdict:** **PASS with Docker caveat.** The API shape and tests match #52, the backend
  test suite passes when forced to rerun, and the Java formatting gate passes after applying
  Spotless. Local DB-backed Testcontainers tests were present but skipped because Docker is
  unavailable in this environment.

---

## Gate - `docs/quality-gates.md`

| Criterion | Result | Evidence |
|---|---|---|
| Service unit and controller tests pass | PASS | `.\gradlew.bat cleanTest test --rerun-tasks` passed. |
| DB migration reviewed; rollback path documented | PASS with process caveat | `V003__seed_coaches.sql` is additive/idempotent and includes a rollback comment. Because this is a migration, owner approval is still the process gate before merge/deploy. |
| Error response follows contract | PASS | Controllers use `ApiErrorException`/`GlobalExceptionHandler` paths and `InternalAuthPrincipal`. |
| No secret exposure | PASS | No secrets or raw user text added. |
| Formatting gate | PASS | `.\gradlew.bat spotlessApply` was run, then `.\gradlew.bat spotlessJavaCheck` passed. |

---

## Findings

1. **[fixed] `spotlessJavaCheck` failed on the new Java files.**
   Initial review found format violations across the new Java files. `.\gradlew.bat
   spotlessApply` fixed them, and `.\gradlew.bat spotlessJavaCheck` now passes.

## Contract Coverage

- `GET /me` and `PATCH /me` exist under `/api/v1/me`.
- `GET /coaches` exists under `/api/v1/coaches`.
- `GET /usage/today` exists under `/api/v1/usage/today`.
- DTOs use `@JsonProperty` for the OpenAPI snake_case fields.
- `PATCH /me` rejects `display_name` longer than 100 chars and rejects
  `is_onboarded=false`.
- `PATCH /me` validates `selected_coach_id` against `coach_profiles`.
- `GET /usage/today` returns `daily_roleplay_limit = 2` and `analysis_limit = null`.
- `V003__seed_coaches.sql` seeds Mia, David, and Sarah and is idempotent on `slug`.

## Verification Run

- `.\gradlew.bat spotlessApply`: passed.
- `.\gradlew.bat spotlessJavaCheck`: passed.
- `.\gradlew.bat cleanTest test --rerun-tasks`: passed, 161 tests total, 37 skipped.
- `git diff --check`: passed.
- Skipped locally: `JdbcUsageRepositoryTests`, `JdbcUserRepositoryTests`,
  `JdbcCoachRepositoryTests`, and other Docker/Testcontainers suites.
- `.\gradlew.bat spotlessJavaCheck`: failed due Java format violations.

## Residual Risk

- The #52 midnight-boundary SQL tests exist, but they were skipped locally because Docker is
  unavailable. CI or a local Docker run should execute them before calling the ticket fully done.
- The implementation is currently uncommitted/untracked on `main`, so there is no branch or PR
  evidence yet.

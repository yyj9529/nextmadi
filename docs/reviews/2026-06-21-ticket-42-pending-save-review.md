# Review - Pending Save Claim (#42)

- **Date:** 2026-06-21
- **Reviewer:** Codex
- **Scope:** working tree on `feat/42-pending-save`
- **Target:** GitHub issue #42, `E06.4 Pending Save - Pre-signup Save Preservation / Attribution`
- **Related exec-plan:** `docs/exec-plans/2026-06-21-pending-save-claim.md`
- **Verdict:** **PASS with residual integration risk.** The backend claim path, BFF route, pending-save client replay, and new-user S03b continuation are implemented and covered by focused tests. The live browser E2E remains blocked until the anonymous analysis flow sets the httpOnly anonymous-session cookie.

---

## Gate 1 - Spec Compliance

| Criterion | Result | Evidence |
|---|---|---|
| Pre-signup S07 Save writes `pending_save` and routes to `/login` | PASS | `ResultActions` writes `{ analysis_request_id, selected_variant_order }` and routes unauthenticated users to `/login`. |
| Authenticated, already-onboarded users with `pending_save` return to S07 and auto-save | PASS | `/auth/complete` renders `PostLoginRedirect`, which reads `pending_save` client-side and routes back to `/save/result/{analysis_request_id}`. |
| New users complete S03b first, then resume pending save | PASS | `CoachSelectExperience` now resolves the post-onboarding destination from `pending_save`; helper tests cover both resume and normal `/home` paths. |
| Backend claim is tied to authenticated `user_id` plus original anonymous `session_token` | PASS | Internal JWS stays `user_id` only; optional request-body `session_token` drives the atomic anonymous claim path. |
| Mismatched token cannot hijack another pending save | PASS | Service and JDBC tests cover mismatch and already-owned row behavior. |

## Gate 2 - Code Quality

| Criterion | Result | Evidence |
|---|---|---|
| Auth boundary remains narrow | PASS | `InternalAuthPrincipal` XOR behavior is not changed. |
| Claim update is atomic | PASS | `JdbcAnalysisRepository.claimAnonymousAnalysis` uses `UPDATE ... WHERE user_id IS NULL AND session_token = ? RETURNING ...`. |
| Tests are meaningful | PASS | Backend claim tests, BFF helper tests, pending-save serialization tests, post-auth routing tests, and post-S03b routing tests are present. |
| No secret exposure | PASS | No secrets found in the reviewed diff. |
| Browser-visible path compiled | PASS | Production build compiles `/api/expressions`, `/auth/complete`, `/welcome/coach`, and `/save/result/[analysis_request_id]`. |

## Fix Applied During Review

The initial review found one blocking issue: new users were sent to `/welcome/coach` after login, but coach selection still routed directly to `/home`, bypassing the pending-save replay. The follow-up fix adds `resolvePostOnboardingDestination` and uses it from `CoachSelectExperience` so S03b completion resumes `/save/result/{analysis_request_id}` when `pending_save` exists.

## Verification Run

- `bun test src/lib/pending-save.test.ts`: failed first as expected because `resolvePostOnboardingDestination` did not exist.
- `bun test src/lib/pending-save.test.ts`: passed, 13 tests after implementation.
- `bun test src/lib/pending-save.test.ts src/lib/expression/save-expression.test.ts`: passed, 17 tests.
- `.\gradlew.bat test --tests "com.phraselog.expression.*" --tests "com.phraselog.analysis.*"`: sandbox network blocked Gradle wrapper download; rerun with approval passed.
- `bun run build`: passed.
- `git diff --check`: passed.

## Residual Risk

- The full live E2E remains blocked until the real anonymous analysis BFF flow sets the httpOnly anonymous-session cookie.
- A concurrent double replay after one request claims the analysis may still need a follow-up re-read path so the second request can treat the now-owned/saved row as success instead of a stale 404.

## Verdict

Ready for commit and PR. The remaining risk is an integration dependency, not a blocker for the backend/BFF/frontend contract delivered here.

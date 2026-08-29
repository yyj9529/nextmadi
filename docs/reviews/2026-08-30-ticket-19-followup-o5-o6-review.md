# Review: ticket #19 follow-up O5/O6

- Target branch: `fix/ticket-19-followup-o5-o6`
- Reviewed commit: `2a3ef0e` (`65d0ba2` is the mistake-log commit and is outside this review)
- Base: `534d131`
- Diff reviewed: `git diff 534d131..2a3ef0e`
- Exec plan: `docs/exec-plans/2026-08-30-ticket-19-followup-o5-o6.md`
- Date: 2026-08-30

This is the independent auth-risk review for the #19 follow-up that closes O5 and O6. I treated
the implementation notes as claims to re-check, not as review evidence.

## Gate 1 - Spec Compliance

| Requirement | Verdict | Evidence |
| --- | --- | --- |
| O5: the console fallback must not be reachable by default | PASS | Missing SMTP now builds an email provider with `unconfiguredSender`, and the send path throws unless `devConsoleAllowed(env)` is true (`src/lib/auth/email-provider.ts:98`, `src/lib/auth/email-provider.ts:137`, `src/lib/auth/email-provider.ts:150`). The opt-in is exact-string `AUTH_EMAIL_DEV_CONSOLE === "true"` and is additionally shut off by `VERCEL`, `VERCEL_ENV`, or `NODE_ENV === "production"` (`src/lib/auth/email-provider.ts:168`). The new tests cover absent switch, Vercel markers, production mode, and local opt-in (`src/lib/auth/email-provider.test.ts:251`). |
| O5: provider construction and sending must stay separated | PASS | `buildEmailProvider()` still constructs when SMTP is missing; the throw is inside `unconfiguredSender` during `sendVerificationRequest` (`src/lib/auth/email-provider.ts:98`, `src/lib/auth/email-provider.ts:137`). The regression test builds under `NODE_ENV=production` without throwing (`src/lib/auth/email-provider.test.ts:243`), and `bun run build` completed with route generation, which exercises Next.js route-module evaluation. |
| O5: real SMTP production delivery must not regress | PASS | When SMTP host/user/password/port/from are configured, the code returns the real Nodemailer provider with the same Korean email sender and 24h max age (`src/lib/auth/email-provider.ts:78`, `src/lib/auth/email-provider.ts:87`, `src/lib/auth/email-provider.ts:93`). Existing and new frontend tests pass, including the configured-provider cases. |
| O5: deployment guard must match the documented deployment surface | PASS for v1 | The documented frontend deployment path is Next.js on Vercel, with Vercel env vars holding the auth and SES SMTP settings (`docs/architecture.md:46`, `docs/architecture.md:83`, `docs/architecture.md:243`, `docs/architecture.md:259`, `docs/architecture.md:265`). There is no separate staging environment in v1 (`docs/architecture.md:261`). If the frontend later runs outside Vercel, this guard will need a new deployment marker, but that is not a current v1 hole. |
| O5: SMTP/config errors must not leak the internal guard message to the login UI | PASS | The thrown message is a server-side configuration message (`src/lib/auth/email-provider.ts:23`). The client maps failed email sign-in responses to the generic send-failed copy (`src/lib/auth/email-signin.ts:17`, `src/lib/auth/email-signin.ts:60`), and the login screen only enters the sent state after `outcome.sent` is true (`src/app/(app)/login/LoginExperience.tsx:51`, `src/app/(app)/login/LoginExperience.tsx:59`). Auth.js non-client-safe errors redirect as `Configuration`, not with the thrown message (`node_modules/@auth/core/index.js:131`, `node_modules/@auth/core/index.js:140`). |
| O6: expired or malformed consumed tokens must not remain usable | PASS | `useVerificationToken()` now converts the consumed backend row and returns it only when `isStillValid(token.expires)` is true (`src/lib/auth/bff-adapter.ts:67`, `src/lib/auth/bff-adapter.ts:73`). `isStillValid()` refuses invalid dates with `Number.isFinite(at)` before comparing against `Date.now()` (`src/lib/auth/bff-adapter.ts:121`). The new tests cover expired, `not-a-date`, and `null` expiry values. |
| O6: backend outages must not be collapsed into expired-link UI | PASS | `consumeVerificationToken()` only maps a 404 with `verification_token_not_found` to null (`src/lib/auth/email-provisioning.ts:107`, `src/lib/auth/email-provisioning.ts:113`). Other HTTP failures still go through the throwing error path, and the 500 regression test passes. |
| O6: single-use semantics must remain intact | PASS | The backend consume path calls the repository consume first, then purges expired rows (`backend/src/main/java/com/phraselog/auth/email/VerificationTokenService.java:85`, `backend/src/main/java/com/phraselog/auth/email/VerificationTokenService.java:91`, `backend/src/main/java/com/phraselog/auth/email/VerificationTokenService.java:101`). The repository implements consume as `DELETE FROM verification_tokens ... RETURNING identifier, token, expires`, so a consumed expired token is still deleted before the BFF rejects it (`backend/src/main/java/com/phraselog/auth/email/JdbcVerificationTokenRepository.java:33`, `backend/src/main/java/com/phraselog/auth/email/JdbcVerificationTokenRepository.java:36`). |
| O6: user-visible expired/reused behavior must stay the same | PASS | Auth.js treats `!hasInvite`, `expired`, and identifier mismatch as `Verification` (`node_modules/@auth/core/lib/actions/callback/index.js:146`, `node_modules/@auth/core/lib/actions/callback/index.js:154`). The app maps `Verification` to the login retry state (`src/lib/auth/oauth-flow.ts:15`, `src/lib/auth/oauth-flow.ts:28`), matching S03's expired/reused link requirement (`docs/screens/s03.md:51`, `docs/screens/s03.md:61`). No project logger or event handler consumes the `{ hasInvite, expired }` details. |
| O6: clock-skew analysis | PASS | The prompt's clock concern appears slightly inverted: Auth.js creates the expiry timestamp in the Next.js runtime before calling the adapter (`node_modules/@auth/core/lib/actions/signin/send-token.js:45`, `node_modules/@auth/core/lib/actions/signin/send-token.js:61`), and the adapter sends that timestamp to the backend (`src/lib/auth/bff-adapter.ts:55`, `src/lib/auth/bff-adapter.ts:60`). The backend DB clock still matters for quota counts and purge timing (`backend/src/main/java/com/phraselog/auth/email/JdbcVerificationTokenRepository.java:59`), but this change does not introduce a new EC2/RDS-versus-Vercel expiry judgment. |
| Requested scope | PASS | The reviewed commit changes only the email provider guard, BFF adapter expiry check/tests, backend consume documentation, auth docs, package manifest, and the exec plan. I did not find new UI behavior, new auth routes, or unrelated product behavior in `2a3ef0e`. |

## Gate 2 - Quality

| Check | Verdict | Evidence |
| --- | --- | --- |
| O5 regression tests are meaningful | PASS | Re-running the targeted tests against an isolated copy with the old `email-provider.ts` restored produced failures for the missing-switch and Vercel-marker console fallback cases. Current `bun test` passes with 294 tests. |
| O6 regression tests are meaningful | PASS | Re-running the targeted tests against an isolated copy with the old `bff-adapter.ts` restored produced failures for expired, invalid-date, and null-expiry consumed tokens. Current `bun test` passes with 294 tests. |
| Date-based tests | PASS | The new adapter tests use a one-hour future timestamp and a one-minute past timestamp, not a near-boundary millisecond assertion, so normal CI scheduling latency should not make them flaky. |
| Dependency pin claim | PASS with repository-artifact fix required | `package.json` pins `next-auth` exactly (`package.json:18`), and installed `next-auth@5.0.0-beta.31` depends on exact `@auth/core: 0.41.2` (`node_modules/next-auth/package.json:79`, `node_modules/next-auth/package.json:80`). However, `bun.lock` still records the root workspace request as `^5.0.0-beta.31` (`bun.lock:9`), which is finding F1. |
| Fresh install reproducibility | PASS | In the current checkout, `bun install --frozen-lockfile` completed with no changes. In a no-`node_modules` archive copy of `2a3ef0e`, `bun install --frozen-lockfile` also completed and installed the pinned `next-auth@5.0.0-beta.31` with `@auth/core@0.41.2`. |
| Auth.js / Nodemailer peer mismatch | NOT INTRODUCED | `@auth/core@0.41.2` marks `nodemailer: ^7.0.7` as an optional peer (`node_modules/@auth/core/package.json:76`, `node_modules/@auth/core/package.json:79`, `node_modules/@auth/core/package.json:88`), while this project uses `nodemailer@9.0.5` (`package.json:19`, `node_modules/nodemailer/package.json:3`). This branch did not introduce the mismatch, the provider uses the basic `createTransport().sendMail()` surface, and the configured-provider tests/build pass. I would track it separately as dependency hygiene, not block O5/O6 on it. |
| Documentation and comments | FAIL | The new docs/comments mostly match the code, but two lines still describe the old dependency state and overstate what `Number.isFinite` validates. See F2. |

## Findings

### F1 - P2 - Commit the lockfile root request after pinning `next-auth`

`package.json` changed the root dependency to exact `next-auth: "5.0.0-beta.31"` (`package.json:18`), and the resolved lock entry is still exact (`bun.lock:619`). But the root workspace dependency stanza in `bun.lock` still says `"next-auth": "^5.0.0-beta.31"` (`bun.lock:9`).

Failure scenario: a later developer runs a normal `bun install` in a clean checkout. In my isolated archive copy, that command rewrote the lockfile root stanza from the caret request to the exact request without changing the resolved package. That creates avoidable lockfile churn and leaves the audit trail for O6 looking half-updated even though frozen installs currently reproduce the intended versions.

Required fix: update and commit the one-line `bun.lock` root dependency request so it matches `package.json`.

### F2 - P2 - Fix the O6 rationale to match the actual pin and parser behavior

The new adapter comment still says `package.json` carries a caret-ranged prerelease (`src/lib/auth/bff-adapter.ts:107`), and `docs/auth.md` says the Auth.js comparison was load-bearing while `package.json` carries a caret range (`docs/auth.md:234`). That is stale after `package.json:18` was pinned exactly.

The same rationale also says that when the backend wire format moves away from ISO, `new Date(...)` becomes `Invalid Date` (`src/lib/auth/bff-adapter.ts:109`, `docs/auth.md:236`). That is true for unparseable values, but not for every non-ISO value. `new Date(1788038062043)` and an RFC date string both produce finite future dates, so `Number.isFinite(at)` is a parseability guard, not strict ISO contract validation.

Failure scenario: a maintainer reads the docs and believes either that the dependency is still caret-ranged, or that the BFF rejects all non-ISO expiry drift. The current code is acceptable for closing the security hole because it refuses unparseable and expired values, but the written rationale overclaims the guarantee.

Required fix: update the docs/comment to say the package used to be caret-ranged and is now pinned, and narrow the wire-format claim to unparseable values. If strict ISO validation is desired, add an explicit string/ISO check before `new Date(record.expires)`.

## Verification

| Command / check | Result |
| --- | --- |
| `git diff --check 534d131..2a3ef0e` | PASS |
| `bun test` | PASS - 294 pass, 0 fail, 842 assertions across 39 files |
| `bun run typecheck` | PASS |
| `bun run lint` | PASS |
| `bun run build` | PASS - Next.js 16.2.9 compiled successfully, generated 22/22 static pages |
| `backend\gradlew.bat spotlessCheck cleanTest test` | PASS - 431 tests, 430 passed, 0 failed, 1 skipped. The skip is the pre-existing disabled live transcription test. I did not run `spotlessApply` in the shared checkout because this is a review-only pass. |
| `bun install --frozen-lockfile` in current checkout | PASS - no changes |
| `bun install --frozen-lockfile` in no-`node_modules` archive copy of `2a3ef0e` | PASS - installed the pinned dependency graph |
| Targeted red-check with old `email-provider.ts` and `bff-adapter.ts` restored only in an isolated copy | FAILS AS EXPECTED - 36 pass, 6 fail across the two targeted test files. The failing cases cover old O5 console fallback behavior and old O6 expired/malformed-token behavior. |

## Notes for the Owner

- O2 is correctly outside this branch and remains a policy decision for issue #152.
- The #19 AC1 production SES round trip remains owner/operator verification, not something this local review can prove.
- `AUTH_EMAIL_DEV_CONSOLE === "true"` is intentionally strict. I agree with keeping `"1"` and `"TRUE"` closed; the server-side error message names the exact accepted value.
- The Vercel guard is enough for the documented v1 deployment shape. A future self-hosted frontend, alternate hosting provider, or persistent staging environment should add an explicit production/deployment marker rather than relying on Vercel env vars.
- The Nodemailer peer mismatch predates this branch. Given passing tests and the small provider API surface used here, I would not block this follow-up on it, but a separate dependency-hygiene issue would be reasonable.

## Verdict

**Changes required before merge.**

Gate 1 passes: I did not find a remaining runtime auth behavior gap for O5 or O6. Gate 2 is blocked by repository-artifact and documentation consistency: commit the lockfile root request update and correct the O6 rationale so the branch's written guarantees match the code.

# Exec plan: Email magic link — SES SMTP (#19)

Status: **Executed 2026-08-27 / 2026-08-28.** All eight units are implemented and reviewed;
see "Final outcome" at the end. The migration (U1) and the `nodemailer` install (U5) were
approved by the owner. SES production access and Vercel env remain outstanding owner tasks,
so acceptance criterion 1 (a real external round-trip) is not yet closable.

Branch: `feat/e03-2-email-magic-link` (based on `origin/main` @ 90eec32).

## Goal

Issue #19 (E03.2): email magic-link sign-in via Auth.js Nodemailer provider over Amazon
SES SMTP. A visitor enters an email at S03, receives a link, and clicking it produces the
same authenticated PhraseLog session that Google/Kakao produce today. Expired links land
on a recoverable UX instead of a dead end.

This also closes an S03 gap that exists today: the same-email-different-provider conflict
tells the user to log in "이메일 링크로" — a path that does not exist yet.

## Source specs

- GitHub issue #19: Nodemailer + SES SMTP, DB-backed verification token store, ~24h
  expiry, expired-link UX, "이메일을 확인해주세요" status, SES sandbox exit.
- `docs/screens/s03.md` — source of truth for behavior. Provider order (Google, Kakao,
  email), the "이메일을 확인해주세요" status state, "링크가 만료됐어요" + re-request, and the
  same-email edge case where email sign-in is the sanctioned escape hatch because mailbox
  ownership is verified.
- ADR-010 + `docs/auth.md` — BFF. The browser never calls Spring Boot; Next.js holds the
  session and calls Spring over `X-Internal-Auth` (HS256, 120s TTL).
- `docs/exec-plans/2026-06-20-nextauth-google-kakao-oauth.md` — owner already chose
  "Spring Boot owns user/identity persistence" for #18. This plan follows that precedent.
- `docs/data-model.md` — `users`, `user_auth_identities` (`provider` already documents
  `'email'` as a legal value). No verification-token table exists yet.
- `SECURITY.md` — dependency install, DB migration, and secrets are approval gates.

## The blocking design problem, and the decision

Auth.js's Nodemailer (Email) provider requires an **Adapter**. Our `src/auth.ts` has none:
it runs `session.strategy: "jwt"` with no adapter, and user provisioning goes out to Spring
via `provisionOAuthIdentity` → `POST /api/v1/auth/oauth/identity`. So "add an adapter"
collides with ADR-010, which says Spring owns the database.

**Decision: implement a thin Auth.js Adapter in Next.js whose storage calls go to Spring
over `X-Internal-Auth`** (U4), rather than installing `@auth/pg-adapter` and pointing
Next.js at RDS.

Why: ADR-010 is Accepted and #18 already settled that Spring owns identity persistence.
A direct pg adapter would put DB credentials in Vercel, open a Vercel→RDS network path
that does not exist today, and create two writers for `users`. That is an ADR-012-sized
change, not an implementation detail. The cost of this choice is real — we hand-implement
part of the Adapter interface instead of importing it — and it is bounded, because under
`strategy: "jwt"` the session-table half of the interface is never called.

If the owner prefers the pg-adapter route instead, this plan needs rework and a new ADR
superseding ADR-010's data-ownership boundary.

## Files expected to change

Backend:
- `backend/src/main/resources/db/migration/V009__verification_tokens.sql` (new)
- `backend/src/main/java/com/phraselog/auth/email/**` (new package)
- `backend/src/main/java/com/phraselog/auth/identity/OAuthIdentityRepository.java` and
  `JdbcOAuthIdentityRepository.java` (extend for email identity linking)
- `backend/src/test/java/com/phraselog/auth/email/**` (new)

Frontend:
- `src/lib/auth/verification-token-client.ts` (new)
- `src/lib/auth/bff-adapter.ts` (new)
- `src/lib/auth/email-identity.ts` (new)
- `src/auth.ts`
- `src/lib/auth/oauth-flow.ts`
- `src/app/(app)/login/LoginExperience.tsx`
- `package.json` / `bun.lock` (nodemailer)
- test files alongside each of the above

Docs / CI:
- `docs/data-model.md`, `docs/api/openapi.yaml`, `docs/auth.md`
- `.github/workflows/lint-test.yml`

## Implementation units

### U1. `verification_tokens` table + Flyway V009

**Goal.** A persistent, single-use store for magic-link tokens.

**Dependencies.** None. **Approval gate: DB migration.**

**Files.** `backend/src/main/resources/db/migration/V009__verification_tokens.sql`,
`docs/data-model.md`.

**Approach.** Auth.js `VerificationToken` shape: `identifier` (the email), `token`,
`expires`. Primary key `(identifier, token)`, plus a unique index on `token` and an index
on `expires` for purging. Add `created_at` for debugging. `identifier` is stored
lowercase to match `OAuthIdentityService`'s normalization.

**Resolved 2026-08-27 (was an open question).** Read against the installed
`next-auth@5.0.0-beta.31`: `@auth/core/lib/actions/signin/send-token.js` stores
`createHash(rawToken + secret)` and emails the raw token, and
`lib/actions/callback/index.js` hashes the URL token the same way before lookup.
`createHash` is SHA-256 hex, so the column always receives 64 characters. The adapter
never sees the raw token and the backend must not hash again. Column is `VARCHAR(255)`
rather than `CHAR(64)` so an upstream hash change is not a migration.

**Patterns to follow.** V001–V008 naming and the existing `data-model.md` table-section
format (SQL block, then prose on the non-obvious constraints).

**Test scenarios.**
- Migration applies cleanly on an empty schema (existing Flyway integration test path).
- Inserting two rows with the same `token` violates the unique index.
- `(identifier, token)` allows the same identifier to hold multiple outstanding tokens.

**Verification.** `./gradlew test` passes with the migration in place; `data-model.md`
documents the table.

---

### U2. Backend verification-token endpoints

**Goal.** Create and consume verification tokens behind `X-Internal-Auth`.

**Dependencies.** U1.

**Files.** `backend/src/main/java/com/phraselog/auth/email/` (controller, service,
repository, DTOs), `backend/src/test/java/com/phraselog/auth/email/`.

**Approach.** Two operations under `/api/v1/auth/email/verification-tokens`:
- `POST` — create. Body: identifier, token, expires.
- `POST /consume` — atomic fetch-and-delete by `(identifier, token)`. Returns the row
  (including `expires`) or 404 when absent.

Consume is delete-then-return in one transaction so a replayed link cannot authenticate
twice. Expiry comparison stays in Auth.js core (it owns the `Verification` error), but
consume also purges rows whose `expires` has passed, so the table does not grow forever.

**Abuse guard.** Reject creation when the identifier already holds N unexpired tokens
(start at 5). Without this, anyone can use our SES quota to mailbox-bomb a stranger.
This is deliberately crude — it is not a general rate limiter.

**Never log the token value.** Log identifier and outcome only.

**Patterns to follow.** `com.phraselog.auth.identity` package layout;
`ObjectProvider<DataSource>` for the config class — **not** `@ConditionalOnBean`
(`docs/solutions/spring-conditional-bean-ordering.md`, 4 recurrences). Error responses go
through `ApiErrorException` per the existing contract.

**Test scenarios.**
- Create then consume returns the stored row.
- Consume twice: second call returns 404 (single-use proven, not assumed).
- Consume with a wrong token for a valid identifier returns 404.
- An expired row is still returned once by consume and is gone afterward.
- 6th outstanding token for one identifier is rejected.
- Request without a valid `X-Internal-Auth` is 401 `internal_auth_invalid`.
- Logs from a create+consume cycle contain no token value.

**Verification.** `./gradlew spotlessApply && ./gradlew spotlessCheck test build` green
(`docs/solutions/spotless-before-push.md`, 13 recurrences — `test` alone does not predict
CI).

---

### U3. Backend email identity resolution

**Goal.** Resolve a verified email address to a PhraseLog user, linking rather than
conflicting.

**Dependencies.** U1.

**Files.** `backend/src/main/java/com/phraselog/auth/email/` (service + controller),
`OAuthIdentityRepository` / `JdbcOAuthIdentityRepository` (add the link-to-existing-user
method), tests.

**Approach.** Two endpoints, not one — this was a plan error caught during implementation.
Auth.js calls `getUserByEmail` inside `sendToken`, i.e. while the link is merely being *sent*,
before anyone has clicked anything. A single create-or-find endpoint would therefore mint an
account for every address typed into the S03 form, verified or not. So:

- `POST /api/v1/auth/email/identity/lookup` — read-only, 404 when unknown. Backs `getUserByEmail`.
- `POST /api/v1/auth/email/identity` — may create or link. Called only after the link came back.
- `POST /api/v1/auth/email/identity/link` — same linking, keyed by user id. Added once U4 showed
  that Auth.js's `updateUser` hands the adapter `{id, emailVerified}` and no address, so the
  address-keyed endpoint above is unreachable from the one path that needs it. Without it the
  linking case is code that never runs in production while its own tests pass.

The address travels in the body, not a query string: an email in a URL lands in access logs.

Resolve behavior, body `{ email }`:

1. `(provider='email', provider_user_id=<normalized email>)` exists → return that user.
2. No email identity, but an active `users` row has that email → **attach** an `email`
   identity to that existing user and return it.
3. Neither → create user + email identity.

Case 2 is the whole point and is where this diverges from `OAuthIdentityService.resolve`,
which throws `account_link_required` in the same situation. The difference is justified:
for Google/Kakao we have not proven the person controls the mailbox; for a clicked magic
link we have. `docs/screens/s03.md` states this explicitly.

**Do not modify `OAuthIdentityService`.** A separate service and endpoint keeps the OAuth
conflict behavior untouched; sharing the repository is enough reuse. `provider_user_id`
is the lowercase email so the existing `UNIQUE(provider, provider_user_id)` does the work.

Clearing `scheduled_deletion_at` on sign-in follows the existing OAuth behavior.

**Test scenarios.**
- New email → creates `users` + `user_auth_identities` row, `created_user: true`.
- Second sign-in with the same email → same `user_id`, no new rows.
- Email matching an existing Google user → links to that same `user_id`, does **not**
  create a second user, does **not** throw `account_link_required`.
- Mixed-case and surrounding-whitespace email normalizes to the same identity.
- A user with `scheduled_deletion_at` set has it cleared and the flag reported.
- Lookup on an unknown address creates nothing — `users` and `user_auth_identities` both
  stay empty. This is the test that stops the S03 form from being an account-minting oracle.
- Lookup finds a user registered through another provider without linking anything.

**Verification.** Backend gate as U2.

---

### U4. Auth.js Adapter backed by the BFF

**Goal.** Satisfy Auth.js's adapter requirement without giving Next.js database access.

**Dependencies.** U2, U3.

**Files.** `src/lib/auth/verification-token-client.ts`,
`src/lib/auth/email-identity.ts`, `src/lib/auth/bff-adapter.ts`, plus tests.

**Execution note.** Test-first. The adapter is a contract implementation; the tests are
the contract.

**Approach.** The HTTP clients mirror `src/lib/auth/oauth-provisioning.ts` exactly — same
`mintInternalAuthToken` call, same error class shape, same `fetcher` injection point for
tests.

**Resolved 2026-08-27 (was an open question).** The required method set is now read off
the installed library rather than guessed:

- `@auth/core/lib/utils/assert.js` asserts exactly three at config build for an email
  provider: `createVerificationToken`, `useVerificationToken`, `getUserByEmail`. The ten
  `sessionMethods` are asserted only for `strategy: "database"`, which we do not use.
- At runtime `lib/actions/callback/handle-login.js` adds two in the email branch:
  `updateUser` when `getUserByEmail` found someone, `createUser` when it did not.
- `getUser` is reached only when a session cookie already exists (signing in while
  signed in). Implement it; it is cheap.
- `linkAccount` is **not** called on the email path — it belongs to the oauth and webauthn
  branches. Do not implement it.

Six methods total: the three asserted, plus `createUser`, `updateUser`, `getUser`.

**Consequence for U3.** Auth.js has no "link this identity" call here. Our
`user_auth_identities` row for `provider='email'` has to be written by the backend during
`createUser` (new user) and `updateUser` (existing user found by email — the linking case).
Both adapter methods therefore call the same idempotent `POST /auth/email/identity`, and
`updateUser`'s `emailVerified` argument is discarded because `users` has no such column.

**Every unimplemented method throws an explicit error.** No `return null`, no silent
no-op. A stubbed method that returns a plausible value turns a missing capability into a
successful-looking login — the exact failure mode in
`docs/solutions/green-build-proves-nothing.md` (11 recurrences across two rows).

**Test scenarios.**
- `createVerificationToken` posts identifier/token/expires and returns the record.
- `useVerificationToken` returns the record on 200 and `null` on 404 (Auth.js's contract
  for "not found").
- Backend 500 propagates as a thrown error — never `null`, which Auth.js would read as
  an invalid link rather than an outage.
- `getUserByEmail` returns null for unknown, the mapped user for known.
- `createUser` and `updateUser` both round-trip through `/auth/email/identity` and return
  the same user for the same address (idempotent).
- `updateUser` ignores `emailVerified` without failing — `users` has no such column.
- A method not implemented throws with a message naming the method.
- Missing `PHRASELOG_BACKEND_BASE_URL` / `INTERNAL_AUTH_SECRET` throws at call time,
  matching `oauth-provisioning.ts`.

**Verification.** `bun test src/lib/auth/` green; `bun run typecheck` clean.

---

### U5. Nodemailer provider + SES SMTP

**Goal.** Actually send the mail.

**Dependencies.** U4. **Approval gate: `nodemailer` dependency install.**

**Files.** `package.json`, `bun.lock`, `src/auth.ts`, `src/auth.test.ts`.

**Approach.** Add the Nodemailer provider with the U4 adapter and a 24h `maxAge` (issue
says "~24h"; S03 says "provider-default expiry (typically 24h)" — pin it explicitly rather
than inheriting a default that could change). SMTP host/port/user/pass and `EMAIL_FROM`
come from env, following the existing `secureCookies`-style injection in `buildAuthConfig`
so tests never touch real env.

**Dev without SES.** When SMTP env is absent and `NODE_ENV !== "production"`, override
`sendVerificationRequest` to print the magic-link URL to the server console. This keeps
the flow developable before SES production access lands. In production, absent SMTP env
**throws at config build** — it must not degrade to a silent no-send.

**Send failure surfaces.** An SMTP error must reject, so Auth.js renders `EmailSignin`
rather than the success screen. "이메일을 확인해주세요" over a mail that was never sent is
the same silent-failure pattern as above.

**Test scenarios.**
- `buildAuthConfig` includes the Nodemailer provider with `maxAge` 86400.
- Production + missing SMTP env → throws.
- Development + missing SMTP env → dev transport selected, no throw.
- Injected transport that rejects → `signIn` rejects; no success path taken.
- Google/Kakao config is unchanged by the addition (regression guard on `src/auth.ts`).
- Secrets never appear in the returned config object's serialization.

**Verification.** `bun test`, `bun run typecheck`, `bun run build` all green.

---

### U6. S03 email UI, sent state, expired-link recovery

**Goal.** The three S03 states that do not exist yet.

**Dependencies.** U5.

**Files.** `src/app/(app)/login/LoginExperience.tsx`, `src/lib/auth/oauth-flow.ts`,
`src/lib/auth/oauth-client.ts`, tests.

**Approach.** `LoginExperience.tsx:11` already carries the instruction for this ticket —
restore the email toggle and form, and drop the "미구현" comment. Button order stays
Google → Kakao → email per S03 AC1.

Error copy in `oauth-flow.ts`: map Auth.js's `Verification` error to "링크가 만료됐어요"
with a re-request affordance, and `EmailSignin` to a send-failure message. Update
`account_link_required` — its current copy deliberately omits the email option because
#19 was unimplemented; S03's spec copy ("기존 로그인 방법이나 이메일 링크로 로그인해주세요")
becomes correct once this ships.

Post-click routing reuses `/auth/complete`, so the `is_onboarded` / `pending_save`
decision tree in S03 AC3 is inherited, not rebuilt.

**Test scenarios.**
- Default state renders three providers in S03 order.
- Empty or malformed email → inline validation, no submit.
- Successful send → "이메일을 확인해주세요" state, form no longer submittable.
- `?error=Verification` → "링크가 만료됐어요" + re-request control.
- `?error=EmailSignin` → send-failure copy, not the expired copy.
- `?callback_error=account_link_required` → updated copy naming the email path.
- Unknown error code → existing default message (no regression).

**Verification.** `/ui-verify` at 390x844 covering default, sent, and expired states;
no console errors; no real email address in the screenshots (`SECURITY.md`).

---

### U7. Contract and doc updates

**Goal.** Keep `openapi.yaml` honest as the internal BFF contract.

**Dependencies.** U2, U3.

**Files.** `docs/api/openapi.yaml`, `docs/auth.md`.

**Approach.** Add the three new email endpoints with `internalAuth` security. Note in
`docs/auth.md` that the Email provider's adapter requirement is satisfied over the BFF,
with the reasoning from the decision section above.

`/auth/oauth/identity` from #18 is also missing from `openapi.yaml`. **Not fixed here** —
see deferred work.

**Test expectation: none** — documentation unit. Verified by reading the spec against the
implemented controllers.

---

### U8. Run frontend tests in CI

**Goal.** Make U4–U6's tests mean something.

**Dependencies.** None (can land first).

**Files.** `.github/workflows/lint-test.yml`, `package.json`.

**Approach.** The frontend CI job runs lint, typecheck, and build — **not `bun test`**.
`src/auth.test.ts` and `src/auth-route.test.ts` exist today and have never run in CI. Every
test this plan writes would be decorative without this.

This is scope-adjacent, and it is included rather than deferred because the plan's own
verification story depends on it. It is one line plus a `test` script.

**Test expectation: none** — CI config. Verified by a deliberately failing test producing
a red CI run, then removing it. Do not verify by observing green.

**Verification.** A PR run shows a "Test frontend" step with a nonzero test count in its
output — not merely a green check (`docs/solutions/green-build-proves-nothing.md`).

## Acceptance criteria

From the issue, made checkable:

1. **Round-trip.** A real external address receives the mail, the link authenticates, and
   the user lands per S03 AC3 (`/welcome/coach` when `is_onboarded=false`, else pending-save
   restore, else `/home`). **Blocked on SES production access.**
2. **Expired token UX.** A link whose `expires` has passed lands on "링크가 만료됐어요" with
   a working re-request, and the token is consumed either way.
3. **Single use.** Clicking a valid link twice authenticates once; the second click shows
   the expired/invalid state.
4. **Same-email linking.** An address already registered via Google signs in by magic link
   to the *same* `user_id` — no duplicate account.
5. **No silent failures.** SMTP failure shows a failure state, never "이메일을 확인해주세요".

Gates: `docs/quality-gates.md` auth gate, two-gate review (`/spec-check s03` then
`/ce-code-review`), and `/ui-verify` for U6.

## Test plan

TDD applies to U3, U4, U5, U6 (behavior changes). U1/U2 are backed by Flyway and
repository integration tests. U7 is docs; U8 is CI config verified by a deliberate red.

Backend: `./gradlew spotlessApply && ./gradlew spotlessCheck test build`.
Frontend: `bun test && bun run typecheck && bun run build`.
Browser: `/ui-verify` at 390x844 for S03's three new states.

Integration tests must actually run — a `BUILD SUCCESSFUL` with Testcontainers silently
skipped proves nothing about U2 or U3.

## Risk areas

- **Auth + token handling.** This is a risky ticket under CLAUDE.md section 9: exec-plan
  first (this file), then an independent reviewer that did not write the code
  (`spec-reviewer` or Codex), verdict in `docs/reviews/`.
- **Token secrecy.** Raw tokens must never reach logs, the ledger, or a screenshot. The
  hashing question in U1 is unresolved until verified against the installed library.
- **Mailbox bombing.** SES quota is spendable by anyone with the form. U2's outstanding-token
  cap is the minimum guard; it is not a rate limiter.
- **Silent-success class.** Three separate places in this plan can fake success: a stubbed
  adapter method, a swallowed SMTP error, and a "sent" screen with no send. All three are
  called out with explicit tests because this pattern has recurred 11 times.
- **Account linking.** U3 case 2 attaches an identity to an existing user. A bug here
  merges two people's accounts. The soft-delete and case-normalization scenarios are not
  optional coverage.
- **ADR-010 boundary.** If the adapter grows to need session storage, revisit — that is
  the signal the BFF-backed approach has outlived its fit.

## Owner actions (blocking, cannot be automated)

1. **SES sender verification + production access** (sandbox exit). Approval takes time;
   start it before implementation, not after. AC1 cannot be met without it.
2. **Vercel env**: SMTP host/port/user/password, `EMAIL_FROM`.
3. **Approve the Flyway migration** (U1) and the **`nodemailer` install** (U5).

## Decision log

- **Adapter over BFF, not `@auth/pg-adapter`.** Preserves ADR-010 and follows #18's
  precedent that Spring owns identity persistence. Cost: hand-implementing part of the
  Adapter interface. Rejected alternative: direct RDS access from Vercel — needs a new
  ADR, new network path, and creates a second writer for `users`.
- **Separate `/auth/email/identity` endpoint** rather than extending
  `OAuthIdentityService`. The email path must link where OAuth must conflict; keeping them
  apart means implementing email cannot regress Google/Kakao.
- **Expiry checked by Auth.js, purged by the backend.** Auth.js owns the `Verification`
  error; duplicating the comparison invites the two sides to disagree.
- **24h pinned explicitly**, not inherited from the provider default.
- **Dev fallback logs the link instead of sending**, and production throws when SMTP env
  is missing. A dev fallback that silently no-ops would hide a broken production config.
- **`bun test` added to CI in-scope** (U8) rather than deferred, because this plan's
  verification is otherwise unenforced.

## Deferred to follow-up work

- Back-filling `/auth/oauth/identity` (#18/#20) into `openapi.yaml`. Real drift, but not
  this ticket's.
- A general rate limiter on auth endpoints. U2's cap is a targeted guard, not that.
- Explicit provider linking from an authenticated settings screen — S03 says out of scope
  for v1.
- Automating the six unautomated mistake-ledger items (`docs/solutions/README.md`).
- **Unifying the auth-provisioning response casing.** `/auth/oauth/identity` (#18, merged)
  and `/auth/email/*` both return camelCase bodies while the rest of the contract is
  snake_case. Owner decision 2026-08-28: keep #19 consistent with its merged sibling
  rather than making the two auth endpoints disagree, and move both at once when
  `/auth/oauth/identity` is back-filled into `openapi.yaml`. Request bodies are already
  snake_case on both.
- One shared Postgres container for the whole backend suite. Today each of the ~16 Testcontainers
  classes starts and tears down its own, and on a slow Docker host that churn intermittently
  exceeds the 60s readiness wait (seen twice while building U2/U3; the new classes carry a longer
  `withStartupTimeout` as a local fix). Sharing one container would cut suite time and remove the
  flake, but it touches every integration test and risks the `disabledWithoutDocker` skip that
  `build.gradle` deliberately relies on.

## Final outcome

All eight units are implemented on `feat/e03-2-email-magic-link`. Two sessions:
U8/U1–U5 on 2026-08-27, U6/U7 plus the review gates on 2026-08-28.

Acceptance criteria:

1. **Round-trip** — still blocked on SES production access and Vercel env, both owner
   tasks. Verifiable in development, where the link prints to the server console.
2. **Expired token UX** — met. `?error=Verification` renders "링크가 만료됐어요" with the
   email form pre-opened, which is the re-request control.
3. **Single use** — met. `DELETE … RETURNING` in one statement; a second click finds
   nothing.
4. **Same-email linking** — met, with tests covering the create, link and
   case-normalization paths.
5. **No silent failures** — met, and now proven rather than asserted: the SES send path's
   `rejected`/`pending` guard has tests, and disabling it turns them red.

Gates: `/ui-verify` covered eight S03 states with no console errors. The two-gate review
is recorded in `docs/reviews/2026-08-28-ticket-19-email-magic-link-review.md`, from four
independent passes that did not write the code.

## What changed after execution

**The plan's own regression guard was the wrong shape, and it cost a P0.** U5 listed
"Google/Kakao config is unchanged by the addition" — a config-object assertion. Attaching
the adapter broke every Google and Kakao sign-in at runtime while that assertion stayed
green, because Auth.js does not scope an adapter to the provider that required it. Two
independent reviewers found it; the implementer did not. Fixed by selecting the config per
request, with a test that drives the actual selection rather than the object's shape.

Ledger consequences (`docs/solutions/README.md`):

- **"라이브러리 실제 호출 지점을 안 읽고 설계 확정" 1 → 2.** Note written
  (`library-call-sites-unread.md`). Three instances across this ticket share one shape:
  what the *email* path calls was read; what the change does to *other* paths was not.
- **"조용한 실패가 성공처럼 보임" 5 → 6.**
- **"Testcontainers 로컬 스킵 → 미검증 통과" 6 → 7**, and the 2026-08-02 measure
  (testLogging aggregation) marked insufficient. It reports skips but still needs a human
  to read them, and it says nothing at all when Gradle reuses a previous run as
  `UP-TO-DATE`. Redesigning it into an automatic block is spun off as its own task.

**Two plan assumptions were corrected during U7.** The response-body casing was thought to
be an email-only inconsistency; `/auth/oauth/identity` (#18, merged) has the same shape, so
the owner chose to keep them consistent and unify both later. And the outstanding-token cap
was specified at store time, which the review showed to be worse than no cap — Auth.js
starts the send first, so the mail goes out and the recipient gets a dead link. Enforcement
moved ahead of the send, with the store-time cap kept as a backstop against count inflation.

**One risk in the plan turned out understated.** "Account linking — a bug here merges two
people's accounts" assumed the addresses in `users.email` were trustworthy. Nothing read
the providers' `email_verified` claim, so an unverified address could reach that column.
OAuth provisioning now refuses an explicitly unverified address on both sides of the BFF.

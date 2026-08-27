# Review: Email magic link — SES SMTP (#19)

- Target: `feat/e03-2-email-magic-link`, `git diff origin/main...HEAD`
- Base: `90eec32` (origin/main)
- Exec plan: `docs/exec-plans/2026-08-27-ticket-19-email-magic-link.md`
- Date: 2026-08-28
- Reviewers: four independent passes that did not author the code — a spec reviewer
  (against s03.md / openapi.yaml / data-model.md / quality-gates.md), plus correctness,
  security, and reliability lenses. Two of the four converged on the same blocker
  independently, from different evidence.

This is the risky-ticket review required by `CLAUDE.md` section 9 (auth change).

## Gate 1 — Spec compliance

| Requirement | Verdict | Evidence |
|---|---|---|
| S03 AC1: Google → Kakao → email order | PASS | `LoginExperience.tsx` provider block, then the email toggle |
| S03 AC2: sends the link, shows "이메일을 확인해주세요" | PASS | sent state is gated on a non-failure result |
| S03 AC3: post-login routing reuses `/auth/complete` | PASS | `sendEmailMagicLink` passes `redirectTo: AUTH_COMPLETE_REDIRECT`; the email branch of the `jwt` callback sets `phraselogUserId` / `isOnboarded` |
| S03 AC4: legal links | PASS | untouched |
| S03 "send failed → never the sent state" | PASS | proven by test after B1's fix batch, not only by reading |
| S03 "expired link → form pre-opened" | PASS | `shouldPromptEmailRetry` + `login/page.tsx` |
| Google/Kakao continue to work | **FAIL → FIXED** | See B1 |
| `verification_tokens` matches `data-model.md` | PASS | `V009__verification_tokens.sql` |
| Consume is atomic single-use | PASS | `JdbcVerificationTokenRepository` uses a single `DELETE … RETURNING`; no read-then-delete window |
| Lookup never creates a user | PASS | `@Transactional(readOnly = true)`; covered by `lookupNeverCreatesAUser` |
| Same-email linking resolves to one `user_id` | PASS | `EmailIdentityService` case 2, with tests |
| Email/OAuth internal-pass separation | PASS (code), PARTIAL (tests) | Each controller demands its own sentinel. Only OAuth-token→email-endpoint is tested; see F2 |
| Raw token never leaks from our side | PASS | Nothing in the email package logs; only `sha256(raw + AUTH_SECRET)` is stored. See R1 for what Auth.js itself puts in the URL |
| Error responses follow the contract | PASS | All new errors go through `ApiErrorException` |
| Migration rollback documented | PASS | `V009` header, including the deploy-order caveat |
| No silent adapter stubs | PASS | Unimplemented methods throw and name themselves |

## Blocker (fixed in this branch)

### B1 — Attaching the adapter globally killed Google and Kakao sign-in

Found independently by the spec reviewer and the correctness lens, with matching library
citations.

`src/auth.ts` set `adapter` at the top level of the config. Auth.js does not scope an
adapter to the provider that required it. With an adapter present:

- `@auth/core/lib/actions/callback/index.js:56` calls `getUserByAccount` on the OAuth
  callback — **before** `handleAuthorized`, i.e. before the `signIn` callback that does
  all of #18's provisioning.
- `handle-login.js:24`'s `if (!adapter) return { user, account }` short-circuit — the
  thing that had kept OAuth away from the adapter — no longer fires.
- Our adapter lists `getUserByAccount` as unimplemented, so it throws. Every Google and
  Kakao login ended in `CallbackRouteError` → `/login?error=Callback`.

Returning `null` instead is not a fix: `handle-login.js:230` then calls `getUserByEmail`,
which our adapter answers from the email-identity lookup, so a returning Google user
raises `OAuthAccountNotLinked` instead of our `account_link_required` — and every path
still reaches `linkAccount`, which also throws.

**Why no test caught it.** The exec plan's U5 regression guard was written as "Google/Kakao
config is unchanged" — a config-shape assertion that never enters the runtime callback
path. `bff-adapter.test.ts` went further and pinned `getUserByAccount` throwing as the
expected behaviour. This is `docs/solutions/green-build-proves-nothing.md` exactly: the
suite was green while two of three providers were dead.

**Fix (owner decision 2026-08-28, `708c110`).** Select the config per request. next-auth v5
supports `NextAuth(async (req) => config)`; `/api/auth/callback/{google,kakao}` now gets a
config with neither the adapter nor the email provider — byte-for-byte the shape that
shipped before #19 — and every other route gets both. The email provider has to leave with
the adapter, or Auth.js rejects the config with `MissingAdapter`
(`@auth/core/lib/utils/assert.js:135`). #18's provisioning is untouched.

Rejected alternative: implementing the OAuth surface (`getUserByAccount`, `linkAccount`)
in the adapter. Cleaner long-term, but it rewrites an already-deployed login path and needs
a new backend lookup endpoint — an ADR-sized change, not a fix.

**Regression guard.** `selectAuthConfig` is exported so the test drives the actual
selection Auth.js will make, not the config object's shape. The fix was reverted locally to
confirm the new test goes red; it does.

## Findings

### Fixed in this branch

**F1 — the real SES send path had no test at all.** `sendKoreanVerificationRequest`'s
`rejected`/`pending` check is the only thing standing between "SES accepted the envelope
but not the recipient" and a "이메일을 확인해주세요" screen — the ticket's own stated top
risk. It was never executed by any test; the plan's U5 scenario "injected transport that
rejects → `signIn` rejects" was listed and not written. Four scenarios now cover it, and
disabling the guard was confirmed to turn them red.

**F2 — SMTP had no timeouts.** Nodemailer's defaults (~2 min connection and socket) are far
too long for an interactive sign-in. Now pinned to 10s/10s/15s.

**F3 — a 404 was read as "not found" from the status code alone.** An undeployed
controller, a wrong base path, or a proxy also answer 404, so an outage rendered as "링크가
만료됐어요" — precisely the behaviour the function's own comment said it avoided. V009's
rollback procedure (deploy the app without the email provider first) creates exactly that
window. Now the error code is checked too.

**F4 — "다른 주소로 다시 보내기" left the previous address in the field.** The user could
re-send to the same mailbox without noticing, and walk toward the outstanding-token cap.

### Open — owner decisions

**O1 — the outstanding-token cap fires after the mail is already gone.**
`@auth/core/lib/actions/signin/send-token.js:48-66` starts `sendVerificationRequest`
first and awaits it together with `createVerificationToken` under `Promise.all`. When the
backend cap rejects, the SMTP send is already in flight. So the cap spends the SES quota it
exists to protect *and* hands the recipient a link with no stored token, which 404s into
"링크가 만료됐어요". A user who requests a link a few times in one day (spam folder,
impatience) reaches five outstanding tokens and from then on receives only dead links.

As placed, the cap provides no quota protection and only breaks links. Enforcement has to
move ahead of the send, which means a check inside `sendVerificationRequest` against a
backend endpoint that does not exist yet.

**O2 — the cap is bypassable, and nothing limits distinct recipients.**
`countUnexpired` keys on the literal lowercased address, so `victim+1@…`, `victim+2@…` are
separate buckets that all deliver to one mailbox. Independently, there is no per-IP limiter
anywhere in the Next.js layer, so an attacker can spray unlimited addresses at five each —
burning SES quota and, more expensively, bounce/complaint reputation. O1 and O2 want to be
solved together.

**O3 — magic-link sign-in adopts accounts whose email was never verified.**
`resolve()` case 2 and `linkByUserId` attach a magic-link sign-in to any active `users` row
holding the address. `users.email` is written from the OAuth provider's claim with no
verification check — nothing in the repo reads Google's `email_verified` or Kakao's
`is_email_verified`. If a provider ever supplies an unverified address, an attacker who
registered claiming `victim@…` receives the victim when the victim later signs in by
magic link. #19 opens this direction: the OAuth path used to refuse with
`account_link_required` rather than merge. Confidence is moderate — it depends on a
provider actually issuing an unverified address — but the fix (gate on the provider's
verified flag) is small and belongs with #18's provisioning.

**O4 — no timeout on any Next.js → Spring Boot call.** Reported as new, but
`oauth-provisioning.ts` (#18, on main) has the identical shape; #19 followed the existing
pattern rather than introducing the gap. Fixing it properly means fixing both clients
together, same as the response-casing decision.

**O5 — the dev fallback logs a live credential.** When SMTP is unconfigured and
`NODE_ENV !== "production"`, the magic-link URL goes to the server console. That is the
feature that lets the flow be developed before SES production access, but the guard is
`NODE_ENV`, not "is this deployed" — a staging box or a container run with a non-production
`NODE_ENV` would disclose sign-in credentials to anyone reading stdout. Vercel builds set
`NODE_ENV=production`, which bounds the exposure today.

**O6 — expiry is enforced only inside a prerelease dependency.** `consume()` deliberately
returns expired rows and leaves the judgment to `@auth/core`, verified against
`5.0.0-beta.31`. `package.json` pins `^5.0.0-beta.31`; `bun.lock` plus CI's
`--frozen-lockfile` holds today's build, but the only enforcement of a security-relevant
lifetime lives outside our code.

### Open — coverage gaps

- **Reverse pass separation.** Only OAuth-token→email-endpoint is tested. Nothing asserts
  that `__email_provisioning__` is rejected by `/auth/oauth/identity`. The code is correct;
  the direction is unproven.
- **"No token in logs."** Listed in the plan's U2 scenarios, never automated. The property
  holds today — nothing in the package logs a token — but it is unenforced against future
  edits.
- **Concurrency.** Two simultaneous first-time resolves for one address both insert and one
  hits `uq_users_email`; two concurrent `linkByUserId` calls likewise. Surfaces as a 500
  rather than a settled outcome. `openapi.yaml` calls the endpoint "idempotent by address",
  which is true sequentially and not concurrently. The same shape exists on the merged OAuth
  path, so it is not new here. `INSERT … ON CONFLICT DO NOTHING` would settle both.
- **Soft-deleted users.** `uq_users_email` is unconditional while lookups filter
  `deleted_at IS NULL`, so an address held by a soft-deleted user raises a constraint
  violation as a 500. Inherited from #18. `scheduled_deletion_at` — the soft-delete case
  that *is* in scope — is handled and tested.
- **Wire-format pinning.** Backend tests deserialize with the same Jackson config that
  serialized, so both sides could drift together while `email-provisioning.ts` reads
  `isOnboarded` / `userId` literally. One raw-JSON assertion would close it. Relevant to
  `expires` in particular: if it ever stopped being an ISO string, `new Date(...)` yields
  `Invalid Date`, the comparison is false, and expired links become permanently valid.

### Noted, no action

- **R1 — the raw token is in the callback URL.** Auth.js puts the token and the address in
  the query string, so both land in Vercel access logs and browser history. Nothing in this
  diff puts them there and nothing here can remove them; single-use consumption is the
  mitigation. The claim "token values are never logged" in `openapi.yaml` is about our
  backend and should be read that way.
- **S03 spec edits in the implementing branch.** The AC block — the binding part — is
  untouched. The UI-states table and two edge-case bullets were rewritten, and the one
  behavioural-looking change (the callback-error copy) reconciles the table with already
  merged #18 behaviour rather than bending the spec to this code. Recorded because a spec
  the author just edited deserves to be visible in its own review.
- **Drive-by fix.** `openapi.yaml` had `#/components/schemas/ErrorResponse`, a schema that
  does not exist; corrected to `Error`. Out of #19's scope but a broken reference in the
  canonical contract.

## Gate 2 — Quality gates (`docs/quality-gates.md`)

**Backend / auth / DB change**

| Item | Verdict |
|---|---|
| Service unit and integration tests pass | PASS — 417 tests, 416 passed, 0 failed, 1 skipped. The skip is the pre-existing `@Disabled` OpenAI live test. Testcontainers actually started; this is not a Docker-off `BUILD SUCCESSFUL` |
| DB migration reviewed; rollback path documented | PASS — `V009` header |
| Idempotency preserved where claimed | PASS sequentially; see the concurrency gap |
| Error response follows the contract | PASS |
| No secret exposure | PASS with O5 noted |

**UI change**

| Item | Verdict |
|---|---|
| S03 GWT satisfied | PASS |
| Browser-visible path verified | PASS — `/ui-verify` at 390x844 covered default, form open, validation error, sending, send failure, `?error=Verification`, `?callback_error=account_link_required`, and an unknown error code. No console errors. Dummy addresses only |
| Loading, error, empty states handled | PASS |

Frontend: 188 tests, 0 failed; lint, typecheck, build clean.

## Verdict

**Ready with fixes — B1 resolved, O1 blocking on an owner decision.**

B1 was the one defect that made the branch unshippable, and it is fixed with a regression
guard that was confirmed to fail against the broken behaviour. F1–F4 are fixed.

O1 should be settled before this merges: as it stands the outstanding-token cap makes
things worse than having no cap, because the mail goes out either way and the recipient gets
a dead link. O2 travels with it. O3–O6 are recordable as follow-up work.

Two acceptance criteria remain unverifiable here: AC1 (a real external round-trip) needs
SES production access and Vercel env, both owner tasks. The flow is verifiable in
development, where the link prints to the server console.

Everything else inspected — the migration, the atomic consume, the lookup/resolve split,
the linking service, token secrecy, and the openapi contract against the controllers —
matched its spec.

# Auth handoff — decided

Status: **Decided 2026-06-10 — Option 1 (BFF). See ADR-010.** This document
records the problem, the options considered, and the chosen mechanism.
`architecture.md` (Authentication section) is the source of truth for
implementation detail.

## The problem

`architecture.md` (Authentication section) currently states:

> 4. NextAuth issues a JWT session token, stored in HTTP-only cookie
> 6. Spring Boot validates JWT signature (shared secret with NextAuth)

This assumes the NextAuth session token is a **signed (JWS)** JWT that Spring Boot can
verify with a shared secret. But Auth.js (NextAuth v5) does **not** do that by default.

Verified against official docs (2026-06-05):
- Auth.js default session strategy is `"jwt"`, which is an **encrypted JWT (JWE)** stored
  in an HttpOnly cookie, encrypted with `AUTH_SECRET`. Source:
  https://authjs.dev/concepts/session-strategies and https://authjs.dev/reference/core
- A JWE is **encrypted**, not merely signed. Spring Boot cannot "verify the signature
  with a shared secret" — it would have to **decrypt** the JWE first, then validate.
- Auth.js JWT is oriented to "same app" use; for a separate backend API, an explicit
  handoff mechanism is recommended.

So the current design will not work as written. We must pick one mechanism.

## Options (pick one before W4)

1. **BFF (Backend-for-Frontend).** The browser never calls Spring Boot directly. Next.js
   route handlers hold the NextAuth session, verify it server-side, and proxy calls to
   Spring Boot over a trusted server-to-server channel. Simplest auth model; adds one
   network hop and requires securing the Next.js→Spring Boot link.
2. **Backend-owned token.** After NextAuth login, mint a separate **signed (JWS)** token
   (e.g., HS256/RS256) that the browser sends to Spring Boot; Spring Boot verifies that
   token's signature. Standard SPA+API pattern; you build a token-issue/refresh path.
3. **Database session lookup.** Use a NextAuth database adapter; Spring Boot reads the
   session table by the session id in the cookie. Revocable; adds a DB read per request.
4. **Provider token verification.** Verify Google/Kakao provider tokens at the backend.
   More moving parts; usually unnecessary if 1 or 2 covers it.

## Decision (2026-06-10)

**Option 1 (BFF)**, superseding the earlier tentative lean toward Option 2.
Full rationale lives in ADR-010. Summary: v1 has exactly one client, the
Next.js app, so a browser-facing token system is surface area without a
consumer. BFF keeps tokens out of the browser, eliminates product CORS, and
removes token storage/refresh design from the v1 critical path. Option 2 remains
the documented upgrade path if a native app or external API consumer appears;
that would need a new ADR.

### Hardening requirements

- Next.js -> Spring Boot requests carry a short-lived signed (JWS) internal
  token in `X-Internal-Auth`, binding `user_id` or anonymous S02
  `session_token` as claims.
- A static key plus plaintext `X-User-Id` header is rejected because any key
  holder could impersonate any user.
- Network allowlisting is not a substitute for the signed token. Vercel function
  egress IP behavior must be verified against official docs at W4 before
  relying on any allowlist.
- Cookie security on the NextAuth session: `__Host-` prefix, `Secure`,
  `HttpOnly`, `SameSite=Lax`.
- CSRF protection lives at the Next.js layer: NextAuth built-ins for its own
  routes, Origin/Referer checks for custom state-changing route handlers.
- Spring Boot still performs resource-level authorization on every request. The
  internal token authenticates caller/user context; it does not replace row
  ownership checks.

### Contract consequence

`docs/api/openapi.yaml` is the internal Next.js BFF -> Spring Boot contract.
Its security scheme is `internalAuth`, carried in `X-Internal-Auth`, not a
browser-facing token scheme.

## Implementation (2026-06-18, #20)

The token-issue/verify pair is implemented (exec-plan
`docs/exec-plans/2026-06-18-internal-auth-jws.md`). Finalized parameters:

- **Algorithm: HS256.** Symmetric, matching the single shared `INTERNAL_AUTH_SECRET`.
  The verifier pins HS256 and rejects `alg=none` and asymmetric algorithms (algorithm-
  confusion guard) — it never trusts the token's own `alg` header.
- **TTL: 120s; clock-skew leeway: 30s.** Minted per request, used for one server-to-
  server hop. The ticket left the exact value to implementation (architecture.md says
  "minutes"); 120s is the chosen value.
- **Claims:** exactly one of `user_id` / `session_token`, plus `iat` and `exp`. No
  `iss`/`aud` in v1 (single known issuer/audience). The exactly-one-of rule is enforced
  on both mint (TS) and verify (Java) sides.
- **Key rotation:** the verifier accepts an ordered list of secrets (tries each), so the
  documented annual two-secret overlap (architecture.md rotation note) is a config change,
  not a redeploy. v1 ships with one secret configured.
- **Failure surface:** every verification failure maps to the existing 401
  `internal_auth_invalid` error contract; the specific reason is not leaked to the caller.
- **Code:** backend `com.phraselog.auth` (filter + verifier); Next.js mint utility
  `src/lib/internal-auth.ts`. Wiring the mint utility into real route handlers is deferred
  to the NextAuth/BFF ticket (those layers do not exist yet).

## Email magic link — adapter over the BFF (2026-08-28, #19)

Auth.js's email provider requires an **Adapter**, which normally means giving Next.js a
database connection. ADR-010 says the database is Spring Boot's, and #18 already settled
that Spring owns user and identity persistence. Those two facts collide.

**Decision: implement the adapter in Next.js and route its storage calls to Spring over
`X-Internal-Auth`**, rather than installing `@auth/pg-adapter` and pointing Vercel at RDS.

Why not the pg adapter: it would put database credentials in Vercel, open a Vercel→RDS
network path that does not exist today, and create a second writer for `users`. That is
an ADR-sized change to the data-ownership boundary, not an implementation detail.

The cost is real and bounded: we hand-implement part of the adapter interface instead of
importing it. Bounded, because under `session.strategy: "jwt"` the session-table half of
the interface is never called. Six methods are implemented —
`createVerificationToken`, `useVerificationToken`, `getUserByEmail` (asserted at config
build by `@auth/core/lib/utils/assert.js` for email providers), plus `createUser`,
`updateUser` (both reached in the email branch of `handle-login.js`) and `getUser`.
`linkAccount` is **not** implemented; it belongs to the oauth and webauthn branches.

**Every unimplemented method throws, naming itself.** No `return null`, no silent no-op —
a stub returning a plausible value turns a missing capability into a successful-looking
login.

If the adapter ever needs session storage, that is the signal this approach has outlived
its fit; revisit ADR-010 rather than widening the adapter.

**The adapter is attached per request, not globally.** Auth.js does not scope an adapter to
the provider that required it: once one exists, the OAuth callback asks it for
`getUserByAccount` (`@auth/core/lib/actions/callback/index.js:56`) before the `signIn`
callback runs, and `handle-login.js:24`'s `if (!adapter)` short-circuit — the thing that had
kept #18's OAuth path adapter-free — stops firing. An adapter that implements only the email
surface therefore kills Google and Kakao sign-in outright.

So `NextAuth` is built from a function of the request (`next-auth/index.js:102`).
`/api/auth/callback/{google,kakao}` gets a config without the email provider and without the
BFF adapter; every other route gets both. They must leave together: an email provider without
an adapter fails config validation with `MissingAdapter`
(`@auth/core/lib/utils/assert.js:135`).

**The callback config still carries an adapter — one that stores nothing.** Leaving the
adapter key absent looks correct and is not, because `assert.js` keeps its provider scan in
module-level state (`assert.js:15-17`, set at `:90-94`, never reset). One request with the
email provider latches `hasEmail` for the life of the process, and from then on every
adapter-less config is rejected with `MissingAdapter` — so the OAuth callback 500s unless it
happens to be the first auth request that process ever sees (#157). `createOAuthCallbackAdapter()`
satisfies that check while answering the OAuth path exactly as no storage would:
`getUserByAccount`, `getUser` and `getUserByEmail` return null, `linkAccount` is a no-op, and
`createUser` hands back the very object it was given — the user the `signIn` callback has
already provisioned. Identity is still created in one place. Everything else throws.

That latch is why the regression test runs **two** requests against one process. A test that
exercises the callback alone passes with the bug present.

The alternative was implementing the OAuth surface in the BFF adapter. It is cleaner in the
long run and is the direction to take if the adapter ever needs to own both paths, but it
rewrites an already-deployed login path and needs a backend lookup endpoint that does not
exist — an ADR-sized change, not a fix. Upgrading past the latch was checked and is not
available: `@auth/core@0.41.3` (the newest release as of 2026-09-01) still declares that
state at module scope.

### Endpoint authority

The six `/auth/email/*` operations are the only endpoints whose caller has no user yet.
They are authorized by a dedicated `session_token` value, `__email_provisioning__`,
rather than a `user_id`. The OAuth provisioning token does not open them and this token
does not open OAuth provisioning, so a leak on one side does not carry the other's
authority.

Lookup is split from resolve for the same reason. Auth.js calls `getUserByEmail` while
the link is merely being *sent* — before anyone has clicked anything — so a combined
create-or-find endpoint would mint an account for every address typed into the S03 form.
See `docs/api/openapi.yaml` for the per-operation contract.

### The outstanding-token cap sits ahead of the send, not the store

An address may hold at most five unexpired links. That cap is checked by
`POST /auth/email/verification-tokens/quota`, which the BFF calls from inside
`sendVerificationRequest` — before anything reaches SES — and **not** while storing the
token.

Auth.js starts the send and the store concurrently and awaits them together
(`@auth/core/lib/actions/signin/send-token.js`). A cap applied at store time therefore fires
after the mail is already in flight: the SES quota the cap exists to protect is spent anyway,
and the recipient receives a link with no row behind it, which they see as "링크가 만료됐어요".
In that position the cap is strictly worse than no cap, which is why enforcement moved.

The store operation keeps the same cap as a backstop. Auth.js does not cancel it when the send
is refused, so without one every refused attempt would still write a row — inflating the count
and locking the address out until expiry. Refusing there is harmless now, because the quota
check already stopped the mail.

The check decides rather than reserves, so a race between the check and the store can exceed
the cap by one. That is accepted — a reservation protocol is more machinery than a
deliberately crude guard warrants. The cap protects one address; it does not stop an
attacker who varies the address, and there is no per-IP limiter on this path yet.

### The console fallback is opt-in, not "not production"

With no SMTP configuration, `email-provider.ts` prints the magic link to the server console
instead of sending it. That path exists so the whole flow can be exercised before SES
production access, and what it prints is a credential: whoever reads that line signs in as
that address.

It opens only when `AUTH_EMAIL_DEV_CONSOLE=true`, and even then not when `VERCEL` or
`VERCEL_ENV` is set or `NODE_ENV` is `production`. Every other case throws, so a missing
configuration fails loudly rather than quietly printing.

The condition used to be `NODE_ENV !== "production"` alone. `NODE_ENV` says whether to build
for production, not whether this process is deployed and reachable — a staging box, or a
container started without passing the variable, satisfied it while being exposed. The default
has to be closed; the two deployment checks are backstops for the switch itself ending up in a
deployed environment's variables.

`AUTH_EMAIL_DEV_CONSOLE` belongs in the local `.env` only. It is never set in Vercel.

### OAuth email verification

`users.email` is the key the magic link uses to find an existing account, so that column must
only ever hold addresses somebody proved they control. OAuth provisioning therefore refuses an
address the provider explicitly marked unverified — Google's `email_verified`, Kakao's
`kakao_account.is_email_verified`. Without that, an account registered while claiming a
stranger's address would swallow that stranger the first time they signed in by magic link.

Only an explicit `false` is refused. A provider that says nothing is allowed through; treating
silence as unverified would block every provider that omits the claim. Both the BFF and Spring
Boot apply the rule, so neither side alone is load-bearing and a mismatched deploy cannot open
the gap.

### Same-email divergence from OAuth

`/auth/email/identity` links an address to an existing user where
`/auth/oauth/identity` raises `account_link_required`. This is deliberate: a provider's
claimed email is a claim, a clicked magic link is proof of mailbox control (s03.md,
same-email edge case). `OAuthIdentityService` is untouched by the email path.

### Token storage and rollback

`verification_tokens` (V010) stores `sha256(rawToken + AUTH_SECRET)`, not the value in
the emailed link — a database leak yields no usable links. Consume is delete-and-return
in one transaction, so a replayed link authenticates once. The backend returns an expired
row once and purges it; it does not judge expiry.

The judging happens above it, in `bff-adapter.ts`'s `useVerificationToken`, which returns
`null` for a token that is expired or whose `expires` does not parse. Auth.js compares
`expires` again (`@auth/core/lib/actions/callback/index.js:147`) and both paths end in the
same `Verification` error, so the user-visible outcome is unchanged.

That duplication is deliberate, for the same reason the OAuth email-verification rule runs
on both sides. The Auth.js comparison was the only enforcement while `package.json` carried a
caret range over a prerelease; the range is now pinned to an exact version, but keeping a
single enforcer would raise the same question at every upgrade.

It is also correct only when `expires` parses. An unparseable value becomes `Invalid Date`,
`NaN < Date.now()` is `false`, and an expired link would read as permanently valid. Our check
asks for a finite timestamp first, so that failure closes the link rather than opening it.
The finiteness check is a parseability guard, not contract validation — an epoch-milliseconds
number or an RFC date string parses, and where the parsed instant is right the verdict is right.
Pinning the wire format itself is a separate open item from the #19 review.

Rollback path for V010 is documented in the migration header: `DROP TABLE
verification_tokens;` as a compensating migration, run after deploying an app build
without the email provider. The table is standalone with no foreign keys in either
direction and holds only single-use rows that expire within 24 hours, so the only loss is
magic links already in flight.

## Related

- `architecture.md` — Authentication and authorization section.
- ADR-005 — stack decision (NextAuth + Spring Boot split).
- Auth.js docs — session strategies (verified 2026-06-05).

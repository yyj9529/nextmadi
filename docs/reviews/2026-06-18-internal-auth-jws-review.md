# Review — BFF Internal Token (X-Internal-Auth JWS) (#20)

- **Date:** 2026-06-18
- **Reviewer:** spec-reviewer (independent; review-only, no code written — separate from
  the implementing agent per CLAUDE.md two-agent handoff)
- **Scope:** branch `feat/20-internal-auth-jws` — backend `com.phraselog.auth/*`
  (filter, verifier, principal, exception, properties, config), `RequestCorrelationFilter`
  `@Order`, `build.gradle` (nimbus-jose-jwt:10.9.1), `application.yml` /
  `application-prod.yml`, Next.js `src/lib/internal-auth.ts`,
  `scripts/mint-interop-fixture.ts`, and the `com.phraselog.auth` test classes.
- **Context read:** #20, ADR-010, `docs/auth.md`, `docs/api/openapi.yaml` (`internalAuth`
  scheme + `security: []`), `SECURITY.md`, `docs/quality-gates.md`, the locked
  `ErrorContractControllerAdviceTests`.
- **Verdict:** **PASS.** The implementation faithfully matches
  ADR-010, the exec-plan, #20 acceptance criteria, and the openapi public allowlist. All
  seven spec-critical properties (HS256 pin, algorithm-confusion rejection, exactly-one-of
  claims on both mint and verify, backend `iat`/TTL enforcement, server-only mint guard,
  fail-closed default, locked 401 contract reuse) are correctly implemented and
  meaningfully tested.

---

## Gate — `docs/quality-gates.md` "Backend / auth / DB change"

| Criterion | Result | Evidence |
|---|---|---|
| Unit + integration tests pass | PASS | `InternalAuthVerifierTests`, `InternalAuthFilterIntegrationTests`, `InternalAuthInteropTests`, `InternalAuthSecretsBindingTests`; targeted auth tests and `spotlessCheck` green. |
| DB migration reviewed; rollback | N/A | No schema change. |
| Idempotency preserved where claimed | N/A | No idempotent endpoint here. |
| Error response follows contract | PASS | Filter's 401 is byte-identical to the locked `ErrorContractControllerAdviceTests` 401; integration test asserts rendered body + correlation echo. |
| No secret exposure | PASS | Exceptions carry only `Reason.name()`; config errors omit the secret value; prod secret has no default fallback. |

---

## Findings — spec compliance

1. **(nit)** Min-32-byte secret enforced symmetrically on both sides (`internal-auth.ts:42-44`,
   `InternalAuthVerifier.java:45-51`). Correct. No action.
2. **(nit)** Exactly-one-of enforced in three places; the TS runtime branch is the weakest
   but the Java verifier rejects blank `user_id` via `isBlank()` → fail-closed on the trust
   boundary. Acceptable. No action.
3. **(minor) Rotation multi-secret path is unit-tested but the Spring config-binding of a
   comma-separated `PHRASELOG_INTERNAL_AUTH_SECRETS` env string → `List<String>` is not
   asserted.** The ordered-list verifier logic is proven, but the binding format ops will
   actually use for the documented annual two-secret overlap (architecture.md:345) is
   untested. **Suggested fix (before relying on rotation in prod):** one test binding
   `phraselog.internal-auth.secrets=secretA,secretB` and asserting a `secretB`-signed token
   verifies.
4. **(nit)** Committed dev-default secret in `application.yml` is a clearly-labeled non-prod
   placeholder; prod (`application-prod.yml`) has no fallback and fails fast if
   `INTERNAL_AUTH_SECRET` is unset. Right call. No action.

## Findings — code quality

5. **(nit)** `InternalAuthException.Reason.MISSING` is declared but never thrown (the filter
   handles a blank header by calling `reject()` directly). Dead constant — optionally remove
   or wire it.
6. **(nit)** `requiresAuth` uses `path.startsWith("/api/v1")`, so `/api/v1foo` would also
   require a token. This is *more* restrictive (fail-closed) and safe. No action; noted as a
   conscious choice.

---

## Edge cases — verified handled

Missing/blank header, malformed JWT, `alg=none` plaintext, algorithm confusion (HS384 →
BAD_ALGORITHM; the token `alg` is only compared-equal to HS256, never used to select a
verifier), missing `exp`, missing `iat`, lifetime longer than 120s, future `iat` at/over
leeway, both/neither claim, non-string claim type, clock-skew boundary (within/beyond
leeway, deterministic via injected `Clock.fixed`), wrong secret, rotation secondary
secret, filter ordering (correlation before auth — proven by the 401 carrying `it-corr-1`
through the real servlet container), public `GET /landing/examples` not gated (mirrors
openapi `security: []` exactly), actuator not gated, jose→Nimbus interop on a real minted
fixture.

## Edge cases — NOT handled (acceptable for scope)

- ERROR/ASYNC dispatch re-entry: not explicitly tested, but `resolveException` writes in
  place (no ERROR re-dispatch) and `OncePerRequestFilter` guards double-execution. No bypass.
- `iss`/`aud` validation: intentionally deferred (plan A2). Documented.
- `nbf`: not validated; low-risk for a 120s same-secret hop, out of the stated claim set.
- Real route-handler wiring (NextAuth → mint → proxy) + CSRF/Origin: explicitly out of scope
  (those layers don't exist yet); deferred to the BFF/NextAuth ticket. All three #20
  acceptance criteria are backend-verifiable and met.

---

## Disposition

- **Acted on before merge:** Finding 3 (rotation config-binding test) and Finding 5 (remove
  dead `MISSING` enum), plus Codex follow-up questions on backend `iat`/TTL enforcement and
  `server-only` mint guarding — addressed on the same branch.
- **No action:** Findings 1, 2, 4, 6 (reviewed, correct as-is).
- Final merge decision is the owner's.

# Exec plan: Anonymous analysis rate limit (#34)

Status: **Implemented in current checkout.** This plan was approved before coding and
executed without a DB migration. It did not require a DB schema migration because
`anonymous_analysis_usage` and `analysis_requests.ip_address` already exist in
`backend/src/main/resources/db/migration/V001__init_schema.sql`.

## Goal

Implement issue #34: pre-signup S02 analysis calls are limited to 2 successful
analyses per IP address per server-local day. The limit applies only when the verified
`X-Internal-Auth` principal carries `session_token`; authenticated `user_id` calls are
unlimited in v1. The third anonymous attempt from the same IP/date must fail before the
AI provider is called, returning HTTP 429 with `error_code = "rate_limit_exceeded"`.

## Source specs

- GitHub issue #34: IP-based pre-signup analysis limit, 2 per day, successful analyses
  only, authenticated users bypass, reset by `usage_date`, shared NAT acknowledged,
  VPN/IP rotation out of scope.
- `docs/screens/s02.md`: S02 anonymous try flow; `session_token` lives in
  `sessionStorage`; BFF sends the anonymous claim in `X-Internal-Auth`; rate-limited UI
  reacts to 429.
- `docs/api/openapi.yaml`: `POST /analysis` is the internal BFF-to-Spring contract;
  anonymous calls carry `session_token` and are subject to `anonymous_analysis_usage`.
- `docs/data-model.md`: `anonymous_analysis_usage(ip_address, usage_date, count,
  updated_at)` and `analysis_requests.session_token/ip_address`.
- `docs/architecture.md`: browser never calls Spring Boot directly; Next.js route
  handlers proxy to Spring Boot with `X-Internal-Auth`.
- `docs/quality-gates.md`: backend/auth/DB gate requires unit and integration tests,
  error contract compliance, and no secret exposure.
- `SECURITY.md`: no live AI cost without approval; DB schema migrations require owner
  approval.

## Key facts established before coding

- The schema already exists:
  - `anonymous_analysis_usage` has primary key `(ip_address, usage_date)`.
  - `analysis_requests` already has nullable `session_token` and `ip_address`.
  - Therefore #34 should not add a migration unless execution discovers a missing
    constraint that cannot be handled in application code. If that happens, stop for
    owner approval before writing a migration.
- The backend currently has `com.phraselog.auth.InternalAuthPrincipal`, exposed by
  `InternalAuthFilter` as a request attribute. It enforces exactly one of `userId` or
  `sessionToken`, and exposes `isAuthenticatedUser()`.
- The current checkout does not yet contain a production `analysis/` package or
  `POST /analysis` controller. #34 should build the reusable usage/rate-limit module and
  define the exact hook that #39's analysis endpoint must call. If implementation happens
  before #39, do not create a fake S07 endpoint just to close this ticket; prove the usage
  module and 429 contract with a test harness, then wire the production endpoint when
  #39 lands.
- The 429 error contract is already represented in
  `ErrorContractControllerAdviceTests`: status 429, `error_code =
  "rate_limit_exceeded"`, retryable true, and the standard `request_correlation_id`.
- Because the browser calls Next.js, not Spring Boot, `request.getRemoteAddr()` is not a
  reliable user-IP source in production. Spring Boot must receive a normalized client IP
  from the trusted BFF boundary.

## Design

### Counting key

Use the existing natural key:

```text
(ip_address, usage_date)
```

- `ip_address`: PostgreSQL `INET`, derived from a single normalized client IP literal.
  IPv4 and IPv6 are both allowed. Do not include ports, comma-separated forwarding
  chains, or arbitrary header text.
- `usage_date`: `LocalDate.now(clock)` using the backend server-local zone, matching
  the issue's "midnight server local time" reset rule. Inject `Clock` in the usage
  service so tests can advance dates without sleeping.
- `session_token` is not part of the counting key. Multiple tabs, cleared
  `sessionStorage`, and multiple anonymous `session_token` values from the same IP all
  share the same quota.

### Client IP source

Add a small `ClientIpResolver` for analysis requests.

Recommended contract:

- Next.js BFF sends `X-Client-IP` to Spring Boot with one normalized IP literal.
- Spring Boot trusts `X-Client-IP` only after `X-Internal-Auth` has been verified. This
  does not make the header browser-trusted; it is an internal BFF handoff field.
- For anonymous `session_token` calls, missing or invalid `X-Client-IP` is a backend/BFF
  contract error. Return a validation-style 400 rather than silently using the BFF's
  remote address in production.
- Local tests and MockMvc should set `X-Client-IP` explicitly.

This should be documented in `docs/api/openapi.yaml` as an internal header on
`POST /analysis`. Keep the IP outside the JWS claim set so #34 does not reopen JWT claim
semantics from #20.

### When to increase `anonymous_analysis_usage`

Use a reserve-then-release pattern.

Reason: the requirement says count successful analyses only, but the rate limit also
needs to reject the third call before an AI provider call. Incrementing only after
success would allow concurrent third/fourth calls to reach the expensive provider when
the stored count is still below 2.

Flow for anonymous `session_token` calls:

1. Resolve verified principal from `InternalAuthPrincipal.REQUEST_ATTRIBUTE`.
2. Resolve `clientIp` from `X-Client-IP`.
3. Compute `usageDate` from injected `Clock`.
4. Atomically reserve a slot with an UPSERT that increments only when current count is
   below 2.
5. If no slot is reserved, throw `ApiErrorException` with status 429 and
   `error_code = "rate_limit_exceeded"` before the analysis pipeline/provider is called.
6. Run the analysis pipeline.
7. If the pipeline and persistence complete successfully and the endpoint returns 201,
   keep the reserved count. No second increment happens.
8. If any terminal failure prevents a successful analysis response, release the reserved
   slot by decrementing the same `(ip_address, usage_date)` row.

The persisted final count therefore represents successful analyses. During in-flight
requests it also includes reserved slots, which is intentional to protect the AI cost
surface. A transient 429 can happen while two anonymous analyses are in flight and one
later fails; the user can retry after the failure releases its reservation. That tradeoff
is better for v1 than allowing extra live provider calls.

### Atomic repository behavior

Create a JDBC repository that owns the quota SQL.

Expected operations:

- `tryReserve(ipAddress, usageDate, limit) -> boolean`
  - Insert `(ip, date, 1, now())` when absent.
  - On conflict, update `count = count + 1` only when `count < limit`.
  - Return true only when insert/update actually reserved a slot.
- `release(ipAddress, usageDate)`
  - Decrement `count` by 1, clamped at 0.
  - Keep zero-count rows. The existing cleanup-job design already bounds old rows, and
    deleting inside the release path adds churn without improving quota correctness.
- `currentCount(ipAddress, usageDate)` for tests only, or package-private test helper
  through repository integration tests.

Do not hold a database transaction open during the AI provider call. The reservation and
release are short DB writes. Holding a connection across an LLM call would be a larger
production risk than the rare overcount-after-process-crash case, and the daily reset
bounds the blast radius of that rare case.

### 429 response

When `tryReserve` returns false, throw:

```text
status: 429 Too Many Requests
error_code: rate_limit_exceeded
retryable: true
developer_hint: Check anonymous_analysis_usage or per-user daily limit counters.
```

The user-facing Korean copy should reuse the existing error-contract wording already
covered by `ErrorContractControllerAdviceTests`; #34 should not create a second
rate-limit response shape.

### Authenticated bypass

If `InternalAuthPrincipal.isAuthenticatedUser()` is true:

- Do not resolve `X-Client-IP`.
- Do not read or write `anonymous_analysis_usage`.
- Do not apply #34's limit.
- Proceed to the normal authenticated `POST /analysis` path.

This matches the issue and S02 edge case: once the BFF sends `user_id`, anonymous usage
is unaffected and the cap does not apply post-signup in v1.

## Files expected to change

Core usage module:

- Create `backend/src/main/java/com/phraselog/usage/AnonymousAnalysisUsageService.java`
  - Orchestrates authenticated bypass, anonymous reservation, and release-on-failure.
- Create `backend/src/main/java/com/phraselog/usage/AnonymousAnalysisUsageRepository.java`
  - Interface for quota storage.
- Create `backend/src/main/java/com/phraselog/usage/JdbcAnonymousAnalysisUsageRepository.java`
  - PostgreSQL UPSERT and release implementation.
- Create `backend/src/main/java/com/phraselog/usage/AnonymousAnalysisReservation.java`
  - Small `AutoCloseable` or explicit object that records whether a reservation needs
    release on failure.
- Create `backend/src/main/java/com/phraselog/usage/ClientIpResolver.java`
  - Validates the internal `X-Client-IP` handoff for anonymous analysis calls.

Analysis integration:

- Modify or create the production `POST /analysis` path only if that path is in scope at
  execution time.
- The analysis service must call the usage reservation before invoking the S07 analysis
  provider, and must release on any non-201 terminal failure.
- If `POST /analysis` is still absent, keep this plan as #39's required integration
  contract and test the service through unit/JDBC tests plus a small test-only harness.

Docs:

- Modify `docs/api/openapi.yaml` to document `X-Client-IP` as an internal BFF-to-Spring
  header for `POST /analysis`.
- Do not modify `docs/data-model.md` or Flyway migrations unless execution discovers a
  true schema gap and the owner approves a DB migration.

Tests:

- Create `backend/src/test/java/com/phraselog/usage/AnonymousAnalysisUsageServiceTests.java`.
- Create `backend/src/test/java/com/phraselog/usage/JdbcAnonymousAnalysisUsageRepositoryTests.java`.
- Create `backend/src/test/java/com/phraselog/usage/ClientIpResolverTests.java`.
- Add `POST /analysis` MockMvc tests in the analysis package when the production
  endpoint exists.

## Implementation sequence

1. Write `ClientIpResolverTests`.
   - Valid single IPv4 header is accepted.
   - Valid single IPv6 header is accepted.
   - Missing header for anonymous analysis is rejected.
   - Comma-separated chains, values with ports, and blank strings are rejected.

2. Implement `ClientIpResolver`.
   - Keep it strict and small.
   - The BFF owns normalization; Spring Boot validates and stores.

3. Write `JdbcAnonymousAnalysisUsageRepositoryTests` with Testcontainers PostgreSQL.
   - First reserve for `(203.0.113.10, 2026-06-19)` succeeds and stores count 1.
   - Second reserve for the same key succeeds and stores count 2.
   - Third reserve for the same key returns false and leaves count 2.
   - Reserve for the same IP on `2026-06-20` succeeds.
   - Reserve for a different IP on the same date succeeds.
   - Release after one reservation decreases the count so another reserve can succeed.

4. Implement `JdbcAnonymousAnalysisUsageRepository`.
   - Use one atomic UPSERT for reservation.
   - Use a bounded decrement for release.
   - Bind `ip_address` through PostgreSQL `INET`; do not store it as arbitrary text.

5. Write `AnonymousAnalysisUsageServiceTests`.
   - Authenticated principal bypasses the repository entirely.
   - Anonymous principal reserves before executing the supplied analysis action.
   - Third anonymous attempt returns 429 and the supplied analysis action is not called.
   - Failed analysis action releases the reservation.
   - Successful analysis action keeps the reservation.
   - Multiple `session_token` values with the same IP/date share the same two slots.

6. Implement `AnonymousAnalysisUsageService`.
   - Accept principal, request/header context, and a callback representing the protected
     analysis work.
   - For authenticated users, call the callback directly.
   - For anonymous users, reserve first, execute callback, and release on exception.

7. Wire into `POST /analysis` when the production endpoint exists.
   - Place the usage check after request validation and principal extraction.
   - Place it before Anthropic/S07 provider invocation.
   - Persist `analysis_requests.ip_address` for anonymous successful analyses.
   - Do not count `POST /transcriptions`; S02 voice transcription is intentionally
     separate and should not consume an analysis try.

8. Add endpoint-level tests when the endpoint exists.
   - Anonymous third call from same IP returns 429 + `rate_limit_exceeded`.
   - The fake S07 provider is not invoked for the 429 request.
   - Authenticated requests do not touch `anonymous_analysis_usage`.
   - Provider failure does not count; a later successful anonymous call from the same
     IP/date can still use the slot.

## Edge cases

- Same IP, multiple `session_token` values: all share the same `(ip_address,
  usage_date)` key. Two successes total, then 429.
- Multiple tabs: each tab can have its own `session_token`, but the server-side key is
  IP/date, so tabs do not bypass the cap.
- Shared NAT: distinct users behind the same office/school/home IP share the cap. This
  is expected v1 behavior; signup is the workaround.
- VPN/IP rotation: a new IP gets a new key and can bypass the cap. CAPTCHA, device
  fingerprinting, and abuse scoring are explicitly out of scope for #34.
- `sessionStorage` cleared: a new `session_token` does not reset quota because the key is
  IP/date.
- Date reset: only `usage_date` changes. The `session_token` can remain the same across
  midnight and still receive a fresh daily allowance.
- Provider/schema/network failure: release the reservation so failed analyses are not
  counted.
- Process crash after reservation: the row may be overcounted until the next reset. This
  is accepted for v1 because avoiding long DB transactions across provider calls is more
  important, and the daily reset bounds the effect.

## Test plan

### Unit tests

- `ClientIpResolverTests`
  - Strictly accepts one IP literal.
  - Rejects missing or ambiguous values.
- `AnonymousAnalysisUsageServiceTests`
  - Authenticated bypass.
  - Anonymous reserve before work.
  - 429 before callback/provider on exhausted quota.
  - Release on failure.
  - Same IP with different session tokens shares quota.
  - Different IP does not share quota.
  - Next-day reset via fake `Clock`.

### JDBC integration tests

- `JdbcAnonymousAnalysisUsageRepositoryTests`
  - Testcontainers PostgreSQL against the real Flyway schema.
  - Atomic reserve limit of exactly 2.
  - Release and re-reserve.
  - IPv4 and IPv6 values store in `INET`.
  - A concurrency test should assert that only two parallel reservations for the same
    IP/date succeed.

### Endpoint tests

When the production `POST /analysis` endpoint exists:

- Anonymous first and second successful calls from the same `X-Client-IP` return 201.
- Anonymous third call from the same `X-Client-IP` returns 429 +
  `error_code = "rate_limit_exceeded"`.
- The fake analysis provider records zero invocations for the third call.
- Authenticated `user_id` calls bypass anonymous usage even with the same IP.
- A simulated analysis failure returns the mapped provider/error response and leaves the
  quota available for a later success.

### No live AI provider verification

Use a fake analysis provider or callback in every #34 test.

- The fake success path returns a minimal valid S07-like result object.
- The fake failure path throws the same exception type the real analysis service would
  surface.
- Tests assert invocation counts to prove 429 happens before the provider boundary.
- No test reads `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, or any live provider credential.
- Do not run S07 live eval for #34. This ticket changes quota behavior, not prompt
  quality or model routing.

## Acceptance criteria

- Anonymous `session_token` calls are limited to 2 successful `POST /analysis` calls per
  `(ip_address, usage_date)`.
- The third anonymous call from the same IP/date returns HTTP 429 with
  `error_code = "rate_limit_exceeded"`.
- The third call is rejected before any S07/Anthropic provider call.
- Failed analyses are not counted in the final daily count.
- Authenticated `user_id` calls bypass this anonymous limit in v1.
- Same IP across multiple session tokens/tabs shares the quota.
- Different IPs get independent quota keys.
- Next server-local date resets the limit.
- No DB migration is introduced unless owner approval is obtained first.

## Out of scope

- CAPTCHA, device fingerprinting, abuse scoring, VPN detection, and IP reputation.
- Per-user authenticated analysis limits; authenticated users are unlimited in v1.
- `POST /transcriptions` rate limiting. S02 STT does not consume an analysis try.
- Live provider smoke tests or eval runs.
- Cleanup job for old `anonymous_analysis_usage` rows; data-model already describes it
  as a separate scheduled job concern.
- NextAuth/BFF route-handler implementation beyond documenting the `X-Client-IP`
  handoff and wiring it when that layer exists.

## Verification commands

- `git status --short --branch`
- `cd backend && ./gradlew test --tests "com.phraselog.usage.*"`
- `cd backend && ./gradlew test`
- `git diff --check`

If Testcontainers/Docker is unavailable locally, still run the unit tests and report that
JDBC integration verification is blocked by the local Docker environment. Do not replace
the PostgreSQL `INET` integration tests with H2; H2 would not validate the important DB
type behavior.

## Risks / notes

- The client-IP handoff is the sharp edge. Counting `request.getRemoteAddr()` in
  production would likely count the BFF/proxy instead of the user. Keep `X-Client-IP`
  explicit and internal.
- The reserve-then-release design protects AI cost under concurrency, but a process crash
  after reservation can overcount until reset. A more exact design would need additional
  schema state for reservations, which is not justified for v1 and would require DB
  migration approval.
- The endpoint integration depends on #39 if `POST /analysis` is still absent. Do not
  blur #34 into full S07 backend implementation; #34 owns quota and the endpoint hook.
- Per CLAUDE.md section 9, this is a backend behavior change with auth-adjacent
  implications, so an independent review in `docs/reviews/` should happen after
  implementation.

## Final outcome

Implemented without a DB migration.

- Added a reusable `com.phraselog.usage` backend module for anonymous analysis quota.
- Anonymous `session_token` calls reserve one `(ip_address, usage_date)` slot before the
  expensive analysis callback runs.
- Authenticated `user_id` calls bypass anonymous usage completely.
- On quota exhaustion, the service throws the standard 429 error contract with
  `error_code = "rate_limit_exceeded"` before the callback/provider boundary.
- Successful analysis callbacks keep the reservation. Failed callbacks release it, so
  only successful analyses remain counted.
- The JDBC repository uses the existing `anonymous_analysis_usage` table and a single
  atomic PostgreSQL UPSERT for reservation.
- `POST /analysis` in `docs/api/openapi.yaml` now documents the internal `X-Client-IP`
  handoff. The production analysis controller is still absent in this checkout, so #39
  must call this module before invoking the analysis provider.
- S02 `TryExperience` now switches to a signup CTA state when its submitter receives
  `error_code = "rate_limit_exceeded"`. The default submitter remains mock-only and does
  not call a live AI provider.

## What changed after execution

Files added or updated:

- `backend/src/main/java/com/phraselog/usage/*`
- `backend/src/test/java/com/phraselog/usage/*`
- `docs/api/openapi.yaml`
- `src/app/(app)/try/TryExperience.tsx`
- `src/components/app/AnalysisModals.tsx`
- `src/app/(app)/app.css`

Verification run:

- `cd backend && .\gradlew.bat test --tests "com.phraselog.usage.*"`: passed.
- `cd backend && .\gradlew.bat test`: passed.
- `bun run typecheck`: passed.
- `bun run lint`: passed.
- `bun run build`: passed.
- `git diff --check`: passed.

No live Anthropic/OpenAI provider call was made.

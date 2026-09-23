# Review: #175 refuse the committed development internal auth secret

- Target branch: `fix/175-refuse-dev-internal-auth-secret` (uncommitted working tree at review time)
- Base: `origin/main` `3842b7b`
- Reviewer: `spec-reviewer` agent (read-only, separate context from the implementer)
- Author: Claude Code
- Exec plan: `docs/exec-plans/2026-09-23-175-refuse-dev-internal-auth-secret.md`
- Date: 2026-09-23

## Gate 1 - Spec Compliance

| Requirement | Verdict | Evidence |
| --- | --- | --- |
| Startup fails when the secret list contains the development value (any position) and neither `local` nor `test` is active; message omits the secret | PASS | Guard runs inside the only `InternalAuthVerifier` bean definition (`InternalAuthConfiguration.java`). The message carries the active profiles only; the test asserts `hasMessageNotContaining` the secret. Spring's comma-list conversion trims items, so `"other, dev-..."` is caught. |
| Local development and the test suite keep working with the default | PASS | `build.gradle` test task sets `spring.profiles.active=test`; README already starts the backend with `--spring.profiles.active=local`. |
| A test covers both paths | PASS | `InternalAuthDevelopmentSecretGuardTests`: 3 refuse, 3 accept, 1 drift check between `application.yml` and the constant. `MockEnvironment` keeps the Gradle system property out, so the refuse tests are not vacuous. |
| `docs/architecture.md` records where the prod profile is set | PASS | Deploy mechanism section points to `backend/deploy/systemd/phraselog-backend.service` (`SPRING_PROFILES_ACTIVE=prod`). |
| Owner decision: allow-list (`local`, `test`) rather than "refuse only under prod" | PASS | `InternalAuthProperties.DEVELOPMENT_PROFILES`. |

Bypass checks with no finding: the filter takes the verifier in its constructor and servlet filters are created at web-server start even with lazy init; bean overriding is off by default; `@Profile("!local")` in `AudioStorageConfiguration` behaves the same under `test`; no `@ActiveProfiles` in the repo; CI runs through Gradle.

## Gate 2 - Code quality

Covered by the same reviewer for test meaningfulness only; `/ce-code-review` was not run separately.

Findings:

1. nit - `prod,local` both active lets the development value through because any matching profile is accepted. Under `prod`, `application-prod.yml` overrides the secret with `${INTERNAL_AUTH_SECRET}`, so reaching it requires an operator to add `local` and also inject the development value. Not the issue's failure mode (profile omitted). Left as is.
2. nit - two refuse tests only asserted `hasFailed()`, so an unrelated startup failure would keep them green. **Fixed:** all three refuse tests now assert the root cause is the guard's `IllegalStateException`. Re-verified by disabling the guard call: 3 refuse tests failed, restored: 7/7 pass.
3. question - IDE runners that bypass Gradle do not get the `test` profile. Only `PhraselogBackendApplicationTests` relies on the `application.yml` default (the other five `@SpringBootTest` classes set their own secret), so the exposure is that one class.
4. question - 117 tests were skipped locally (Docker unavailable, plus live tests). CI must show the Testcontainers suites running under the `test` profile.

## Verdict

`pass` with notes. No blocker. Finding 2 fixed; 1 accepted; 3 recorded; 4 is a CI check before merge.

## Notes for the owner

- Confirm the PR's Backend CI job reports the Testcontainers suites as run, not skipped.
- AWS Secrets Manager is not created yet; when it is, putting the development value into `INTERNAL_AUTH_SECRET` will stop startup, which is intended.

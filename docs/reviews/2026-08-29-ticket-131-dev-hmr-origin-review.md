# Review: dev HMR origin allowlist (#131 / PR #135)

- Target: PR #135 / branch `fix/131-dev-hmr-origin` / `git diff origin/main...HEAD`
- Reviewed commit: `997294a`
- Reviewer: Codex
- Author: Claude Code, per the two-agent handoff exec-plan
- Related exec-plan: `docs/exec-plans/2026-08-29-ticket-131-dev-hmr-origin.md`
- Date: 2026-08-29

## Gate 1 - Spec compliance

- [x] Satisfies the stated acceptance criteria: local `next dev` usage works from both `localhost` and `127.0.0.1`.
- [x] AI output schema is not applicable.
- [x] No unrequested behavior or scope creep beyond the exec-plan.
- [x] Error-response contract is not affected.

Findings: none.

Review scope answers:

1. Security boundary: pass. The branch adds only `allowedDevOrigins: ["127.0.0.1"]` in `next.config.mjs:13`. Installed Next 16.2.9 builds its dev allowlist from `*.localhost`, `localhost`, and configured `allowedDevOrigins` at `node_modules/next/dist/server/lib/router-utils/block-cross-site-dev.js:77-82`; it checks only internal dev endpoints (`/_next` and `/__nextjs`) at `node_modules/next/dist/server/lib/router-utils/block-cross-site-dev.js:63-72`, and rejects websocket/internal requests whose `Origin` host is not allowed at `node_modules/next/dist/server/lib/router-utils/block-cross-site-dev.js:101-107`. The call sites are guarded by development-only branches at `node_modules/next/dist/server/lib/router-server.js:295-296` and `node_modules/next/dist/server/lib/router-server.js:614-615`. Runtime verification on port 30131 returned HMR `101 Switching Protocols` for `Origin: http://127.0.0.1:30131` and `Origin: http://localhost:30131`, while `Origin: http://evil.example.com` and `Origin: http://192.168.157.1:30131` were still rejected with Next's own "Blocked cross-origin request" warning. This is enough evidence that the change does not open arbitrary local-network origins; it adds one loopback hostname to a dev-only allowlist.

2. Root cause vs symptom masking: pass. The rejection happens before the hot reloader handles `/_next/webpack-hmr`: `router-server.js:614-615` calls `blockCrossSiteDEV`, then `router-server.js:630-634` handles the HMR request only after that check passes. With the new allowlist entry, the same HMR endpoint upgrades for `127.0.0.1`; with an unlisted origin it is still blocked. That makes the origin allowlist the proximate cause for the `127.0.0.1` failure, not a hidden application-code hydration bug. There is one intentional limitation: opening the dev server from another unlisted hostname, including the printed network URL, can still reproduce the frozen-dev-client behavior. That is consistent with Next's dev safety model and is not a blocker for this PR unless mobile/LAN browser verification becomes a required workflow.

3. PR body rebuttal of the issue observations: supported. The issue's "`localhost` resolves to IPv6 here and does not connect" observation is false in this checkout: `curl -I` returned HTTP 200 for `http://localhost:30131/try`, `http://127.0.0.1:30131/try`, `http://[::1]:30131/try`, and `http://192.168.157.1:30131/try`. The likely failure mode was a too-short timeout during first route compilation, not IPv6 reachability. That mistake made the repro step tell reviewers to open the one host, `127.0.0.1`, that was missing from Next's dev allowlist. The proxy hypothesis is also unsupported: `netsh winhttp show proxy` reports direct access, the environment has no proxy-specific variables such as `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY`, and the dev-server log itself emits the cross-origin block warning for rejected HMR origins.

4. Scope: pass, with a follow-up doc task. The code diff is intentionally narrow: `git diff origin/main...HEAD` changes only `next.config.mjs`, and the seven-line config/comment addition is sufficient for #131. No test fixture or app-code change was missing. The documentation gap should stay post-merge, as the exec-plan says: `docs/quality-gates.md:37-38` requires browser-visible verification without saying which server it assumes, `docs/quality-gates.md:54` says browser/manual evidence is linked in PRs without naming the dev-server premise, and `docs/ai-ticket-operating-map.md:162` can be read as build/type/lint-only frontend verification. `.claude/skills/ui-verify/SKILL.md:14` already assumes a local dev server, so it only needs a command hint if the owner wants it.

## Gate 2 - Code quality

- [x] No needless abstraction; this is the smallest supported Next config change.
- [x] Testable; the meaningful risk is covered by HMR-origin runtime checks and standard frontend verification.
- [x] Auth, cost, logging, and backend error handling are not touched.
- [x] No secret exposure; no raw user text added.
- [x] Migration and rollback are not applicable.

Findings: none.

Verification run:

- `git diff --name-status origin/main...HEAD`: only `M next.config.mjs`.
- `git diff --check origin/main...HEAD`: passed.
- `node_modules/.bin/tsc.exe --noEmit --incremental false`: passed.
- `node node_modules/eslint/bin/eslint.js next.config.mjs`: passed.
- `node node_modules/next/dist/bin/next build`: passed with Next 16.2.9.
- `bun test`: 123 pass / 0 fail. The first sandboxed attempt failed with EPERM reading files under `C:\Users\ywj95\Desktop\madi-2`; the approved read-capable rerun passed.
- `node node_modules/next/dist/bin/next dev --port 30131` plus curl HMR checks:
  - `Origin: http://127.0.0.1:30131` -> `101 Switching Protocols`
  - `Origin: http://localhost:30131` -> `101 Switching Protocols`
  - `Origin: http://evil.example.com` -> rejected
  - `Origin: http://192.168.157.1:30131` -> rejected
- HTTP reachability checks returned 200 for `localhost`, `127.0.0.1`, `[::1]`, and the printed network address on port 30131.

## Verdict

`pass`.

Matching gate: UI change / local development verification. The browser-visible path requirement is satisfied by the PR's recorded headless Chrome evidence plus this review's independent HTTP and HMR-origin checks. No code changes are required before merge.

## Notes for the owner

- If future browser QA must run from a phone or another LAN device using the network URL, that is a separate policy decision. This PR deliberately keeps network-origin HMR blocked.
- Merge follow-up: clarify the dev-server premise in `docs/quality-gates.md` and `docs/ai-ticket-operating-map.md` as the exec-plan recommends.

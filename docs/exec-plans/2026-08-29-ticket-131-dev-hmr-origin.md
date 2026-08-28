# Exec plan: dev HMR origin blocks hydration (#131 / PR #135)

- Ticket: #131 (bug, frontend) — under `next dev` pages render server-side but never
  hydrate, so typing and clicking do nothing. Until this fix, browser verification had
  to run against `bun run build && bun run start`, which made the browser gate on every
  screen ticket expensive enough to skip.
- PR: #135, branch `fix/131-dev-hmr-origin`, base `main`
- Date: 2026-08-29
- Reviewer: Codex — a different agent from the one that wrote the code (CLAUDE.md
  section 9)
- Status: implemented (7 lines), CI green, reviewed `pass`

## The change

One line in `next.config.mjs`, plus a comment recording why.

```js
allowedDevOrigins: ["127.0.0.1"],
```

Claimed root cause: the Next dev server blocks requests to its internal dev resources —
including the HMR websocket upgrade — when the request `Origin` is not on its dev
allowlist. `localhost` is on that list by default; `127.0.0.1` is not. Opening the app
on `127.0.0.1` therefore fails the upgrade (Chrome reports
`ERR_INVALID_HTTP_RESPONSE`), and because the Turbopack dev client hydrates on top of
that connection, the page freezes in its server-rendered state.

## Verified locally, independently of the author (2026-08-29, madi-2 worktree)

`.next` deleted, then `bun run dev`. Hydration was judged by setting a value on the
`/try` textarea, dispatching a React `input` event, and reading the character counter —
if the counter moves, React handlers are alive.

| Origin | without the fix | with the fix |
|---|---|---|
| `http://127.0.0.1:3000` | `0 / 500` (dead) | `21 / 500` (works) |
| `http://localhost:3000` | `11 / 500` (works) | `11 / 500` (works) |

The fix was removed and restored on the same tree, so this is causation, not
correlation. It confirms the PR's claim that the failed websocket was the cause rather
than a co-symptom.

HMR upgrade handshake with the fix applied, against `/_next/webpack-hmr`:

| `Origin` header | response |
|---|---|
| `http://127.0.0.1:3000` | 101 |
| `http://localhost:3000` | 101 |
| `http://evil.example.com` | connection dropped (rejected) |

Foreign origins are still blocked. The allowlist did not widen; one loopback address
was added to it.

Production is unaffected, verified against the installed Next 16.2.9 source rather than
the docs claim. `allowedDevOrigins` is read in exactly one place,
`next/dist/server/lib/router-utils/block-cross-site-dev.js`, and both call sites sit
behind development-only guards: `router-server.js:296` (`if (development)`) and
`router-server.js:615` (`if (opts.dev && development)`).

## Review scope handed to Codex

1. **Security boundary.** Does adding a loopback address to `allowedDevOrigins` cross
   any line in `SECURITY.md`? With the dev server reachable on the LAN
   (`192.168.x.x`), does this open a door the handshake table above does not cover?
2. **Root cause vs symptom masking.** Is this the actual cause, or does it hide
   something else — for instance the Turbopack HMR client abandoning hydration whenever
   its connection fails? If the latter, the same incident returns from any other
   unlisted origin.
3. **The PR's rebuttal of two observations in the issue.** The issue claimed (a)
   `localhost` resolves to IPv6 and does not connect, and (b) a proxy sits between the
   browser and the dev server. The PR says both are false. Does that rebuttal hold, and
   if so, how did the wrong observation mislead the repro steps?
4. **Scope.** Beyond the seven-line config change, is anything missing — in particular
   the fact that `docs/quality-gates.md` never states which server its browser
   verification assumes.

## Review outcome

Codex returned `pass` on both gates with no findings:
`docs/reviews/2026-08-29-ticket-131-dev-hmr-origin-review.md`.

It added one fact this plan did not cover: the printed network URL
(`http://192.168.157.1:3000`) is **still rejected**. Browser QA from a phone or another
LAN device would still hit the frozen-dev-client behavior. That is Next's dev safety
model working as designed, and it is a separate policy decision — current mobile checks
use viewport emulation, so nothing is blocked today.

## Post-merge follow-up

An exploration pass found no document that states the "production build only"
workaround outright; it was implicit. But three places never say which server the
browser gate runs on, and should now name `next dev`:

- `docs/quality-gates.md:38`, `:54` — browser verification with no server premise
- `docs/ai-ticket-operating-map.md:162` — reads as if frontend verification is
  `bun run build` only
- (optional) `.claude/skills/ui-verify/SKILL.md:14` — already assumes a dev server;
  only needs the launch command spelled out

Those edits land in a separate commit after the merge. The diff of PR #135 stays as is.

## Candidates for the mistake ledger

- New pattern: **an unverified observation written into an issue as fact, which then
  misled the repro steps.** Issue #131 said "`localhost` does not connect, open
  `127.0.0.1`" — that single line pointed everyone at the one address that triggers the
  bug. First occurrence, so it goes in the dev-log only, no note.
- `stale-next-cache` (currently 4): the PR body again carries "delete `.next` after
  switching branches" as a reviewer warning, and this session had to do the same.

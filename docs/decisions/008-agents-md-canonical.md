# ADR-008: Single-authored agent rules (CLAUDE.md) with AGENTS.md as Codex entrypoint

Date: 2026-05-31
Status: Proposed

## Context

The development environment uses Claude Code and Codex from W1 (CLAUDE.md "Subagent strategy"). `CLAUDE.md` is the working agreement, but Codex reads `AGENTS.md` by convention, not `CLAUDE.md`. Today only `CLAUDE.md` exists, so a Codex session opens without the plan-before-doing rule, the context-loading policy, the approval matrix, or the security rules. That is the precise failure ADR-007 and CLAUDE.md exist to prevent — present on the Codex half of a dual-tool setup. Maintaining two full hand-written copies would reintroduce the drift ADR-007 forbids.

## Decision

`AGENTS.md` at the repo root is the rules file Codex reads, and it must never diverge from `CLAUDE.md`. Exactly one file is authored; the other is non-authoritative. Two acceptable mechanisms:

1. `CLAUDE.md` stays the authored source; `AGENTS.md` is a short pointer ("Canonical agent rules live in CLAUDE.md; read it first") plus any Codex-only notes.
2. If Codex does not follow pointer indirection reliably, `AGENTS.md` is generated from `CLAUDE.md` by a build step (the same pattern as the ChatGPT/NotebookLM bundle scripts).

The owner selects the mechanism when first running Codex, after confirming Codex's actual read behavior. The binding rule is single authorship plus a mechanical (not memory-based) sync.

## Why

Cross-verification (Claude Code implements, Codex reviews independently, per docs/harness.md) is only trustworthy if Codex knows the spec-compliance and security rules it is reviewing against. An uninformed reviewer produces false confidence. Single authored source is the only sync method that survives solo development — ADR-007 already identifies synchronization across duplicate documents as the operation most likely to be skipped.

## Consequences

Both tools enter every session with identical rules. This ADR is a precondition for the cross-verification pattern, not an independent nicety. A pointer or generated `AGENTS.md` is kept in sync by mechanism; editing one file by hand and the other from memory is the failure mode to avoid.

## Why not

- **Two independently authored files.** Drift — the exact failure ADR-007 forbids.
- **Drop CLAUDE.md, keep only AGENTS.md.** Claude Code loads `CLAUDE.md` by convention; dropping it loses Claude-side auto-loading.
- **Do nothing (CLAUDE.md only).** Codex runs uninformed; the cross-verification loop becomes unsafe rather than redundant.

## Related

- ADR-007 — single source of truth; this applies it to the two-tool rules problem.
- `CLAUDE.md` — authored working agreement.
- `docs/harness.md` — cross-verification pattern that depends on this.

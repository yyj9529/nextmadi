# Documentation maintenance

Cross-references go stale when a document is created, renamed, or changes status and
the references to it elsewhere are not updated. This file lists, per change type, which
files to update, and a staleness check to run before committing. Following it prevents
the "(forthcoming) label on a doc that already exists" class of drift.

## Stale-prone patterns

- "Related documents" cross-reference lists at the bottom of each doc
- `(forthcoming)` labels — the named doc may already exist
- hard doc counts ("N planning artifacts")
- ADR ranges and the decision index ("001~00N")
- structure trees (README, ADR-007)
- the always-load set, which is mirrored in four places
- bare doc paths in root files (`README`, `START_HERE`, `PROJECT_CONTEXT` referencing a
  `docs/` file without the `docs/` prefix)

## Triggers and required updates

**Create a new ADR**
- Add a one-line row to `docs/decisions/INDEX.md`.
- Bump the ADR range and table in `README.md`, and any `001~00N` reference in
  `START_HERE.md` and `docs/PRD.md`.
- Status is `Accepted` only after cross-validation or implementation experience.

**Create a new doc**
- `grep` the repo for any `(forthcoming)` label naming it and remove the label.
- Add it to the `README.md` structure tree and documentation hierarchy.
- Add a row to the `CLAUDE.md` file responsibility map if it owns a new topic.
- If it is always-loaded, add it to all four sync points below.

**Create a new folder**
- Amend `ADR-007` (structure) or note it in `docs/harness.md`.
- Add it to the `README.md` structure tree.

**Rename or move a file**
- `grep` the repo for the old name and path, update every reference. Do not rely on
  memory — the reference may be in a doc you have not opened this session.

**Change the always-load set** (the four sync points)
- `CLAUDE.md` session entry sequence + context loading policy
- `AGENTS.md` session entry
- `README.md` "Always loaded" list
- `docs/harness.md` context loading policy

These four must agree. A change to one without the others is the drift this file exists
to prevent.

**A count changes**
- Prefer not to hard-code counts; they go stale silently. Keep only `14 screens` and the
  ADR range, each in as few places as possible. Avoid "N planning artifacts" style counts.

## Staleness check (run before commit)

From the repo root:

```
# 1. forthcoming labels — confirm each remaining hit is GENUINELY not-yet-created
grep -rn "forthcoming" . --include=*.md

# 2. old ADR range — update the N as ADRs are added
grep -rn "001[~-]00[0-9]" . --include=*.md

# 3. hard doc counts — expect none (or one intentional place)
grep -rnE "[0-9]+ (planning )?(artifacts|project docs|documents)" . --include=*.md

# 4. stale pointer to a nonexistent index file
grep -rn "decisions/README" . --include=*.md

# 5. bare docs paths in ROOT files only — expect docs/-prefixed
grep -nE "\`?(architecture|data-model|AI_PIPELINE|EVAL_PLAN|PRD)\.md\`?" \
  README.md START_HERE.md PROJECT_CONTEXT.md CLAUDE.md AGENTS.md
```

"stale 0" means: checks 1–4 return only genuinely-forthcoming items and current ranges,
and check 5 shows only `docs/`-prefixed paths. A clean pass is the precondition for
commit. This can later become `scripts/check-docs.sh` run in CI; until then it is a
manual pre-commit step.

## Related

- ADR-007 — documentation structure these references describe.
- `CLAUDE.md` — file responsibility map and session entry.
- `docs/harness.md` — always-load context policy (one of the four sync points).

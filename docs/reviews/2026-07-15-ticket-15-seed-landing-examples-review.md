# Review: Seed landing_examples (ticket #15)

- **Implementer**: Opus 4.8 (copy + injection SQL)
- **Reviewer**: spec-reviewer (independent, read-only) — different perspective per
  the implementer ≠ reviewer rule.
- **Date**: 2026-07-15
- **Change**: `V007__seed_landing_examples.sql` + migration test assertions.

## Verdict: PASS

Schema-conformant, idempotency correctly implemented for a table without a unique
constraint (per-row `NOT EXISTS` on `korean_text`), 12 seed rows safely cover the
S01 `ORDER BY random() LIMIT 3` sample, and tests verify the spec-relevant minimum.

## Conformance summary

| Requirement | Verdict | Basis |
|---|---|---|
| Column names / defaults match schema | PASS | Inserts only `korean_text`; `id`/`is_active`/`created_at` default (V001:33-38) |
| v1 keeps all seed rows active | PASS | No `is_active` in INSERT → DB default `true`; data-model.md:115 |
| Idempotency guard prevents dupes | PASS | `WHERE NOT EXISTS ... le.korean_text = v.korean_text` (V007) — `ON CONFLICT` unusable (no unique constraint) |
| Enough rows for LIMIT 3 | PASS | 12 rows; test asserts `>= 3` active after real migration |
| Copy matches S02 placeholder tone | PASS | All 12 rows first-person desire/goal statements; no grammar-drill framing |
| Flyway ordering | PASS | V007 next sequential after V006, no gap/collision |
| Rollback documented | PASS | Executable DELETE-by-korean_text list in header, matches V003 convention |
| Structure test covers seed shape | PASS | MigrationSqlStructureTests asserts INSERT + NOT EXISTS |
| Runtime test verifies seed behavior | PASS (partial) | FlywayMigrationTests asserts active count `>= 3` after real Testcontainers migration |

## Review notes and how they were resolved

1. **Copy tense mix (reviewer's one substantive item).** First draft mixed
   desire-framed rows ("…하고 싶어요") with past-regret narration ("…먹었어요"). A
   tapped past-regret card pre-fills /try with a completed-event sentence rather
   than the desire form the placeholder trains, and desire framing is also a
   cleaner S07 analysis input. **Resolved**: all 12 rows rewritten to desire/goal
   framing while keeping the regret context.
2. **No runtime idempotency assertion** — guard is string-checked only. Accepted
   as-is: Flyway runs a versioned migration exactly once, so double-application is
   not a production path; the guard is defensive.
3. **Docker-gated coverage** — the active-count assertion lives in
   `@Testcontainers(disabledWithoutDocker = true)` and no-ops without Docker.
   Consistent with the existing migration-test pattern; runs in CI with Docker.

## Local verification

- `./gradlew spotlessApply spotlessCheck test` → BUILD SUCCESSFUL.
- MigrationSqlStructureTests (structure) ran and passed.
- FlywayMigrationTests (Testcontainers, real V001–V007 chain + active-count `>= 3`)
  skipped locally — no Docker on the dev box; executes in CI.

## Not executed (owner approval required)

- Live RDS injection / deploy (SECURITY.md). This change delivers the migration
  file and local verification only.

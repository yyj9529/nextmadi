# Exec plan: Seed landing_examples (ticket #15)

## Goal

Populate the `landing_examples` table so S01 landing has real rows to sample.
Ticket #15 was reclassified PARTIAL on 2026-07-06: `coach_profiles` is already
seeded (V003, mia/david/sarah). The only remaining gap is `landing_examples`,
which was created in V001 but never populated (0 seed rows).

## Source specs

- GitHub issue #15: remaining scope is landing_examples seed rows.
- `docs/ai-native-dev-guide.md` card #15: model = Opus 4.8 (persona/copy
  quality) + Codex (seed injection SQL). Reason: example copy must fit the
  product philosophy ("대본 없이 반응할 자신감"), which is Opus territory.
- `PROJECT_CONTEXT.md`: primary user (Korean immigrants in the US), primary
  pain (emotional precision, cultural nuance, assertiveness, post-failure
  self-blame). Product principle: reduce shame, build from real user language.
- `docs/screens/s01.md`: S01 samples `SELECT * FROM landing_examples WHERE
  is_active = true ORDER BY random() LIMIT 3`; tapping a card pre-fills /try.
  Zero-examples state is handled gracefully (examples section omitted).
- `docs/screens/s02.md`: /try input placeholder
  "예: 친구한테 서운한 마음을 정중하게 표현하고 싶어요" — landing example copy
  should match this first-person situation-description tone, since a tapped
  card pre-fills exactly this input.
- `docs/data-model.md` (landing_examples): columns id / korean_text / is_active
  / created_at; v1 keeps all seeded rows active (no rotation until v1.1+).

## Deviation note (model assignment)

Card assigns Codex to the injection SQL. This run has Opus writing both the
copy and the injection SQL, because the SQL is mechanical and mirrors the
existing V003 seed pattern (idempotent INSERT). Independent review is done by
`spec-reviewer` (different perspective, read-only), preserving the
implementer ≠ reviewer rule.

## Files expected to change

- Create `backend/src/main/resources/db/migration/V007__seed_landing_examples.sql`
  - Insert a pool of Korean situation examples, `is_active = true`.
  - Idempotent per row (NOT EXISTS on korean_text) so re-running never
    duplicates; mirrors V003's "don't duplicate or clobber" intent.
  - Rollback documented in a comment (DELETE by korean_text set).
- Modify `backend/src/test/java/com/phraselog/db/MigrationSqlStructureTests.java`
  - Add a structural assertion that V007 seeds landing_examples.
- Modify `backend/src/test/java/com/phraselog/db/FlywayMigrationTests.java`
  - Assert at least 3 active landing_examples rows exist after migration (S01's
    LIMIT 3 sample needs at least 3 active rows to always fill).

## Copy scope

12 examples covering diverse recurring situations from user research: medical,
workplace, school/teacher, neighbor/community, service and returns, and
relationship repair. First-person, situation-first, matching the /try
placeholder tone. No grammar-drill framing; each is a "moment I couldn't say
what I meant" description.

## Verification

- `./gradlew spotlessApply && spotlessCheck test` (Testcontainers runs the full
  migration chain V001–V007 on a clean Postgres 16 and asserts seed rows).
- Independent spec review by `spec-reviewer` against s01.md / s02.md /
  data-model.md → verdict in `docs/reviews/`.

## Out of scope / not executed

- Live RDS injection and deploy: owner approval required (SECURITY.md). This
  plan only creates the migration file and verifies it locally in
  Testcontainers.
- CTR-based rotation / activation logic: deferred to v1.1+ (s01.md, PRD 4.3).

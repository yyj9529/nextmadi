# S07 alignment implementation self-review

- Date: 2026-09-19
- Target: current working-tree candidate, including new untracked files; pre-existing unrelated edits excluded.
- Related plan: `docs/exec-plans/2026-09-18-s07-evaluation-alignment.md`.
- This is an implementer self-review, not the independent two-agent review required before merge.

## Gate 1 — Spec compliance

Implemented: preflight intent selection, conservative nonsense rejection, clear short-input acceptance, three result branches, assessment before cards, original-preservation instructions and mandatory behavior-first evaluation. Legacy output reads and expression-only saving are covered by offline tests. Desktop card order was corrected during review.

Unverified: actual model adherence to the semantic rules, bilingual review of all historical expectations, judge calibration and the complete persisted browser journey. The rule-based preflight is deliberately incomplete; arbitrary noun phrases/utterances outside its vocabulary can be misclassified. The generation prompt is the second ambiguity defense, not proof that this issue never occurs.

## Gate 2 — Code quality

Fixed with regression coverage:

- Judge calibration formerly let deterministic route failure hide a judge's false approval. Calibration now inspects the judge's checks directly.
- `uncertain` could look like a correct rejection of a bad fixture; it now fails calibration.
- Judge errors could discard a paid generation result; trial output is retained before judging.
- A catastrophic quality score could continue a full run; scores below 2 now produce a stop status.
- Calibration preserves operational error classifications rather than reporting all errors as semantic failures.

Compatibility and limits:

- No new dependency, external dictionary or database migration. Existing dirty files were preserved.
- Anonymous successful word/clarification results retain the existing quota accounting. Preflight is before reservation and AI.
- Same-process request locking reduces duplicate calls. It is not a distributed exactly-once guarantee; initial anonymous cookie loss is another remaining retry boundary.
- Existing roleplay/save request helpers pass regression tests. Rendering tests stub audio and actions and do not certify real TTS or database persistence.
- The new runner checkpoints fixed synthetic data only. It has no general interrupted-run resume or migrated legacy report-only draft metrics.
- Python Anthropic SDK is unavailable in the bundled runtime checked here. No installation or API call was attempted.

## Evidence

- Frontend affected suites including result rendering: 102 passed, 0 failed.
- Python evaluator/legacy regression: 49 passed, 0 failed.
- Backend selected analysis/expression/schema suites: 72 total, 56 passed, 0 failed, 16 Testcontainers tests skipped.
- Typecheck passed. `eslint src` passed; full `eslint . --ignore-pattern '.pytest_cache/**'` passed after the ordinary command hit a cache-directory permission error. `git diff --check` passed.
- Browser `/try`: word choice and submit enablement, word-to-expression supplement guidance, three short/imperfect positive controls and nonsense rejection verified without submitting an AI request. Screenshot inspected.
- Actual AI validation: not run. Full paid suite: not run. Independent review: not run. Build/deployment: not run.

## Verdict

Not ready for final adoption. The implementation candidate and recorded offline checks are available; outstanding model, DB/browser and independent-review gates must not be marked passed. No commit, push or deployment performed.

## Packaging verification — 2026-09-20

The isolated S07 branch preserves newer main documentation and excludes unrelated branding edits. Frontend tests passed 359/359 and Python tests passed 49/49. The first local frontend run had one pre-existing auth test time out; the unchanged targeted test and full suite then passed under the same default timeout. No quality threshold was changed.

OpenAPI reference validation found an undefined ErrorResponse reference copied from existing endpoints into the new conflict response. References in this candidate now use the existing Error schema. Actual AI validation, live browser persistence and independent review remain pending. Commits and a draft PR are packaging checkpoints, not adoption approval.

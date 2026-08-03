# Quality gates

Per-change quality gates for PhraseLog. A single project-wide quality score is
deliberately not used — PhraseLog quality is multi-dimensional (naturalness,
accuracy, cultural fit, tone, schema validity, cost, latency) and a single number
hides which dimension regressed. Each change type passes its own gate.

Thresholds here mirror `EVAL_PLAN.md`; when they disagree, `EVAL_PLAN.md` is
authoritative for eval numbers and this file is corrected.

## S07 analysis (prompt or analysis-code change)

- `s07_analysis_v1` JSON schema validates.
- S07 mini eval passes (trial-averaged per ADR-009): aggregate ≥ 4.0/5.0; no single
  dimension averages below 3.5; no case below 2.0 on any dimension.
- No regression vs `main` baseline: aggregate drop ≤ 0.3, per-dimension drop ≤ 0.5,
  no increase in cases with any dimension below 2.0.
- Prompt version bumped if prompt content changed.
- `ai_request_logs` fields preserved (`feature_name`, `model_name`, `prompt_version`,
  `latency_ms`, `estimated_cost_usd`, `status`, `request_correlation_id`,
  `attempt_group_id`, `attempt_number`, `is_final_attempt`).
- One row written per attempt, not per call — a retried call writes every attempt
  (`AI_PIPELINE.md` Logging contract).
- No raw user text written to `eval/runs/`.

## S12 roleplay (turn logic, coach prompt, or session-state change)

- Turn-state tests pass (no stuck or out-of-order turns).
- Result summary (`s12b`) output schema validates.
- Estimated cost per session logged and within target.
- Coach utterance ratio checked (coach does not dominate the turn).
- Tier 2 eval criteria apply once Tier 2 is active (post-launch); until then, manual
  review of a sample session is the gate.

## UI change

- Relevant screen GWT (`docs/screens/sNN.md`) satisfied.
- Browser-visible path verified via `/ce-test-browser` or a recorded manual check.
- Loading, error, and empty states handled.

## Backend / auth / DB change

- Service unit and integration tests pass.
- DB migration reviewed; rollback path documented.
- Idempotency preserved where the endpoint claims it.
- Error response follows the contract (see `docs/harness.md` "Error response
  contract").
- No secret exposure (see `SECURITY.md`).

## How gates are enforced

- S07 gate: `.github/workflows/eval.yml` (CI), blocking on regression criteria.
- Tests and lint: CI on PR.
- Browser and manual checks: evidence linked in the PR description.
- The owner is the final merge gate; gates inform that judgment, they do not auto-merge.

## Related

- `EVAL_PLAN.md` — authoritative eval thresholds and CI integration.
- ADR-009 — trial-averaged scores consumed by the S07 gate.
- `SECURITY.md` — backend gate's secret-exposure rule.
- `docs/harness.md` — error response contract and verification step.

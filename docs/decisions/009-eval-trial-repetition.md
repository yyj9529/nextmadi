# ADR-009: Eval trial repetition for variance measurement

Date: 2026-05-31
Status: Proposed

## Context

EVAL_PLAN Tier 1 (S07) scores each case once: per-case score is the mean of four dimensions, the aggregate is the mean across cases. Both the generation step (the S07 prompt) and the judge are LLM calls, so both are non-deterministic. A single run conflates true quality with run-to-run noise. The regression gate (aggregate drop greater than 0.3 blocks CI) can fire on noise, or miss a real regression hidden behind one lucky sample. The eval currently cannot tell a stable case from a flapping one.

## Decision

Each eval case runs N trials, starting at N=3. For each case, all N per-dimension scores are recorded; the case score becomes the mean across trials, and the per-case variance (or score range) is recorded alongside it. Aggregate pass and regression criteria operate on trial-averaged case scores. The run artifact under `eval/runs/` stores per-trial raw scores, not only the averages, so variance is queryable after the fact.

## Why

Non-determinism is a property of the system under test, so the eval must measure it rather than be confused by it. Three trials is the minimum that separates a stable case from a flapping one without an unacceptable cost increase — a Tier 1 run is ~$0.05–0.10 (estimated, per `AI_PIPELINE.md`), so 3 trials is ~$0.15–0.30, still trivial. High variance is itself a signal: a prompt that sometimes emits an unnatural variant is a real defect even when its average passes. This is orthogonal to the self-evaluation trap — an agent grading its own work is already handled by the independent judge; trial repetition handles run-to-run noise.

## Consequences

EVAL_PLAN section 4 scoring is updated to define N, trial-averaging, and variance recording. The CI regression check compares trial-averaged baselines. A case whose variance exceeds a threshold (threshold TBD after first multi-trial run) is flagged for prompt hardening even when its mean passes. Cost per run rises roughly N-fold; acceptable at Tier 1 volume. N is revisited for Tier 2 and Tier 3, where session-level runs are more expensive.

## Why not

- **N=1 (status quo).** Noise is indistinguishable from a real regression.
- **Large N (10+).** Cost and runtime without proportional signal at Tier 1 case counts.
- **Average only, discard per-trial scores.** Loses the variance signal that motivates the change.

## Related

- ADR-003 — three-tier eval; this extends its Tier 1 scoring.
- EVAL_PLAN.md — Tier 1 scoring and regression thresholds updated by this decision.
- `docs/quality-gates.md` — gates consume trial-averaged scores.

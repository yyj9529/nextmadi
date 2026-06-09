# EVAL_PLAN.md

Source of truth for how PhraseLog's AI output quality is measured, regressed against, and improved over time. Implements ADR-003's three-tier eval structure.

This document defines the strategy and conventions. The actual cases, judge prompts, and runner scripts live in `eval/s07-analysis/` (Tier 1) and will live in `eval/s12-roleplay/` (Tier 2, later).

## Why three tiers

Different AI features need different evaluation approaches, on different timelines:

| Tier | What it measures | When it starts | Status |
|------|-----------------|----------------|--------|
| Tier 1 | S07 single-shot analysis quality | W1–3 | Active development |
| Tier 2 | S12 multi-turn roleplay quality | Post-launch, after ~50 real sessions accumulated | Not started |
| Tier 3 | Golden dataset + production dashboard | W17+, after model comparison phase | Not started |

The separation matters because evaluating "did the LLM produce a good 3-variant analysis" is mechanically different from evaluating "did the coach maintain character across 8 turns while keeping user-perceived feedback useful." Mashing both into one eval pipeline would force compromise on both.

## Tier 1 — S07 mini eval

### Scope

Tests S07 analysis output only. Validates the `s07_analysis_v1` JSON schema (defined in `AI_PIPELINE.md`) and the quality of the three variants returned.

Initial size: 10–20 test cases at the end of W3. Expands to ~50 by W12 launch and beyond.

### Test case structure

Cases live in `eval/s07-analysis/test_cases.json`. Each case is a JSON object:

```json
{
  "id": "s07_001",
  "input_text": "친구한테 서운한 마음을 정중하게 표현하고 싶어요",
  "tone_intent": "정중한",
  "category": "emotional_expression",
  "expected_behaviors": [
    "All 3 variants are grammatically correct American English",
    "At least 1 variant matches the requested polite tone",
    "Cultural tip is appropriate for US adult social context",
    "tone_label is generated dynamically, not picked from a fixed enum"
  ],
  "expected_failure_modes": [
    "Variants too casual when polite tone requested",
    "Literal translation that is grammatically correct but unnatural",
    "Cultural tip generic ('use politely' instead of specific situational guidance)"
  ]
}
```

Fields:
- `id`: stable identifier; never renumbered when cases reorder
- `input_text`: the Korean situation as a user would type it
- `tone_intent`: optional; when present, judge checks variant_order=1 matches this tone
- `category`: for trend analysis ("emotional_expression", "service_interaction", "professional", etc.)
- `expected_behaviors`: positive criteria the judge looks for
- `expected_failure_modes`: negative criteria; if observed, that dimension drops

Cases are added by the owner manually for now. When user data accumulates (W13+), real anonymized inputs become candidates for the case pool.

### Judge prompt

Lives in `eval/s07-analysis/judge_prompt.md`, versioned alongside test cases.

Judge model: Anthropic Claude — see `AI_PIPELINE.md` for the verified model used at evaluation time. Quality of judging matters more than speed, so the higher-tier model (currently Sonnet) is used rather than the cheaper option (currently Haiku).

The judge receives:
1. The system prompt explaining the rubric
2. The test case (input + expected behaviors + expected failure modes)
3. The actual S07 output (3 variants in `s07_analysis_v1` JSON shape)

The judge returns a JSON object with four Likert scores (1–5):

```json
{
  "naturalness": 4,
  "accuracy": 5,
  "cultural_appropriateness": 4,
  "tone_match": 5,
  "rationale": {
    "naturalness": "Variants 1 and 3 sound natural. Variant 2 has a slightly awkward collocation.",
    "accuracy": "All three correctly convey the original Korean meaning.",
    "cultural_appropriateness": "Tips reference US-specific norms (e.g., direct expression of disappointment).",
    "tone_match": "Variant 1 matches the requested polite tone clearly."
  },
  "failure_modes_observed": []
}
```

Each rationale must be one or two sentences. The `failure_modes_observed` array references entries from `expected_failure_modes` by partial string match.

### Scoring

**Per-case score**: average of the four dimensions (range 1.0–5.0).

**Aggregate score** for a run: arithmetic mean of all per-case scores.

**Pass criteria** (a run is considered passing):
- Aggregate score ≥ 4.0/5.0
- No single dimension (naturalness, accuracy, cultural, tone) averages below 3.5 across all cases
- No case scores below 2.0 on any dimension (catastrophic failure threshold)

**Regression detection** (PR blocking criteria):
- Aggregate drops more than 0.3 from the last `main` baseline → CI fails
- Per-dimension average drops more than 0.5 from baseline → CI fails
- Number of cases with any dimension < 2.0 increases → CI fails

The CI workflow `.github/workflows/eval.yml` computes these comparisons and posts the diff as a PR comment.

### Running locally

```
python eval/s07-analysis/run_eval.py
```

Reads `eval/s07-analysis/test_cases.json`, runs S07 against each case (using the prompt under `prompts/s07/v{current}.md`), feeds each output through the judge, writes results to `eval/runs/{timestamp}_{git_sha}.json`.

Cost per full run (10–20 cases): roughly $0.05–$0.10 based on the per-action estimates in `AI_PIPELINE.md`. Verified at runtime by summing `estimated_cost_usd` across the run's logged calls.

### CI integration

`.github/workflows/eval.yml` triggers on:
- PRs that touch `prompts/s07/**`
- PRs that touch `eval/s07-analysis/**`
- PRs that touch backend code in the analysis service

The workflow:
1. Runs the eval against the PR branch
2. Runs the eval against `main` for baseline (cached if recent)
3. Posts the diff as a PR comment
4. Sets CI status pass/fail per regression criteria above

### Timeline

| Week | Tier 1 activity |
|------|----------------|
| W1–3 | Build initial 10–20 cases. First judge prompt v1. First run script. |
| W4–8 | Run on every prompt or analysis-code PR. Iterate prompt v2, v3, etc. |
| W9–12 | Stabilize. Cases expand to ~30 covering edge categories. |
| W13+ | Maintenance + selective additions from real anonymized inputs |

## Tier 2 — S12 roleplay eval

### Scope

Evaluates multi-turn conversational quality:
- Coach in-character consistency across turns
- Coach utterance appropriateness (length, register, vocabulary)
- Per-turn feedback usefulness (Haiku-driven)
- Result summary quality (Sonnet-driven S12b output)
- Cost per session vs target

These are not measured the same way as Tier 1. A natural-sounding S07 variant can be judged in isolation; a coach turn must be judged in conversational context.

### Why post-launch

Tier 2 cannot run reliably before real user inputs exist. Pre-launch scripted conversations don't capture the variance of real users (different proficiency levels, different conversational styles, different topic transitions). Pre-defining "success criteria" before observing real data would lock in assumptions that the data may invalidate.

Per `CLAUDE.md` senior solo pattern: spec on demand, not preemptively. Tier 2's evaluation criteria will be defined after observing the first ~50 real sessions.

### Trigger to start

Start Tier 2 design and case selection when:
- v1 has been live for at least 4 weeks
- At least 50 completed `practice_sessions` exist in production
- At least 10 distinct users have completed sessions (single-user data risks overfitting)

Target: W13–16.

### Approach (high-level)

1. Sample real sessions from `practice_sessions` + `practice_turns`
2. Define quality dimensions based on observed patterns (placeholders: flow, character consistency, utterance length appropriateness, feedback signal-to-noise, cost adherence)
3. Build judge prompt that takes the full session transcript and rubric
4. Score sampled sessions
5. Establish baseline; future prompt iterations evaluated against it

Detailed Tier 2 spec will be added to this document as a "Tier 2 — operational details" section when activation triggers fire.

## Tier 3 — Golden dataset and dashboard

### Scope

A curated dataset of production AI outputs, labeled by native English speaker validators (the owner's recruited network), becomes the ground-truth dataset for high-confidence A/B testing of prompts and models.

A dashboard surfaces longitudinal trends in cost, latency, error rate, and (eventually) quality scores, sliced by model version, prompt version, and feature.

### Why post Tier 1 and Tier 2

The dataset requires:
- Production data flowing (real user analyses and sessions)
- Validator network active
- Sufficient volume to make labeling effort worthwhile (probably 200+ samples per category)

The dashboard requires:
- Clear questions to answer (which is what Tier 1 and 2 surface)
- Stable schema in `ai_request_logs` to query against
- Cost budget for the tool (Langfuse, Braintrust, custom Streamlit — open question)

### Trigger to start

W17+, alongside the model comparison phase (ADR-004 operate-period). Concretely:
- After Tier 1 and Tier 2 are both running with stable baselines
- After the owner has identified 2–3 specific A/B tests worth running (e.g., Sonnet 4.6 vs successor, prompt v3 vs v4)

### Approach (high-level)

1. Recruit native validators (existing network: medical professional friend, writer friend, etc.). Compensation model: TBD.
2. Sample production outputs, anonymized
3. Validators label using the same rubric as Tier 1's judge prompt — produces ground truth for "what humans actually think is good"
4. Compare LLM-judge scores against human labels to validate the automated eval
5. Once judge-human correlation is established, the judge becomes high-confidence
6. Run A/B tests using validated judge on full production traffic

### Open questions for Tier 3

- Dashboard tool: Langfuse hosted, Braintrust, or custom Streamlit on EC2
- Validator compensation rate and frequency
- Inter-validator agreement requirements before a label is accepted

## Cross-cutting standards

### JSON-only judge outputs

Every judge returns structured JSON, validated against a schema before scoring. Free-text-only judges are not used — they make trend analysis impossible.

### Versioning

- Test case files: when the case pool's evaluation rubric changes substantively, bump file name (`test_cases.v2.json`). Old versions preserved for back-comparison.
- Judge prompts: versioned as `judge_prompt.v1.md`, `judge_prompt.v2.md`. Run output records which version produced each score.
- Run output: stored under `eval/runs/{ISO8601}_{git_sha_short}.json`. Never edited after write.

### Cost tracking

Each tier's monthly eval cost is tracked from `ai_request_logs.estimated_cost_usd` filtered by call origin (a `request_correlation_id` prefix or a dedicated `feature_name = 'eval'` row).

Initial estimates from `AI_PIPELINE.md`:
- Tier 1: ~$0.05–$0.10 per run × ~10 runs per week = ~$2–$5 per month
- Tier 2: TBD; depends on sample size
- Tier 3: depends on dashboard tool and labeling volume

A cost alert (per `architecture.md` AI cost alerts section) triggers if eval monthly cost exceeds $50 unexpectedly.

## Open questions

1. **Tier 1 judge model lock-in vs upgrade** — When Anthropic releases a newer Claude (e.g., 4.7), do we upgrade the judge automatically or freeze for trend stability? Industry consensus is to freeze for trend continuity and re-baseline when upgrading.
2. **Tier 1 case count for v1 launch** — minimum bar is 20 cases; target stretch is 30. Decision before W12.
3. **Tier 2 activation criteria** — currently "50 sessions, 10 distinct users." Tune from actual production volume observed in W13–14.
4. **Tier 3 dashboard tool** — Langfuse vs Braintrust vs custom. Decision in W17.
5. **Validator network formalization** — informal favors vs paid contract vs both. Decision before Tier 3 activation.

## Related

- ADR-003 — three-tier eval system; this document implements it
- ADR-004 — 24-week roadmap; eval phases align with operate period
- `AI_PIPELINE.md` — judge model, S07 output schema, cost reference
- `architecture.md` — `.github/workflows/eval.yml` CI integration, AI cost alerts
- `data-model.md` — `ai_request_logs` table that backs cost tracking
- `eval/s07-analysis/test_cases.json` — actual cases
- `eval/s07-analysis/judge_prompt.md` — judge rubric
- `eval/s07-analysis/run_eval.py` — runner script
- `prompts/s07/v{N}.md` — prompts being evaluated

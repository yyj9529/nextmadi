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

Size: 22 hand-written cases at first baseline (2026-06). Expanded to 74 on 2026-09-05 by
filling the scenario coverage taxonomy below, and to 82 on 2026-09-06 with eight
implicit-cue twins (see "Implicit-cue pairs"). Grows past 100 only from confirmed
real-input failures, not from more hand-written hypotheses.

What this set can and cannot say. The cases are owner-written hypotheses that fill a
coverage matrix; they are not a random sample of real user input. A run therefore
supports one claim, "this prompt scores higher or lower than the previous baseline on
the same cases", and not "the product works for N percent of real users". The second
claim needs inputs sampled from the target population, which is the first assumption in
Indeed's LLM-evaluation statistics note (verified 2026-09-06,
https://engineering.indeedblog.com/blog/2026/07/bootstrap-confidence-intervals-for-llm-evaluation/ ).
When real anonymized inputs accumulate (W13+), they replace hypotheses case by case and
this paragraph is revisited.

Why this size. Anthropic's eval guidance says 20–50 tasks drawn from real failures is a
sound starting set because early changes have large, visible effects; its statistics note
warns that small sets give wide confidence intervals, so runs are compared paired against
the previous baseline rather than read as absolute scores. Practitioner guidance (Hamel
Husain) starts from a features x scenarios matrix and lets coverage, not a round number,
drive growth. The 74 figure is what the matrix below needs, not a target in itself.
Sources, verified 2026-09-05:
https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents ,
https://www.anthropic.com/research/statistical-approach-to-model-evals ,
https://hamel.dev/blog/posts/evals-faq/

### Scenario coverage taxonomy

The case set is built on two axes so that gaps are visible as empty cells rather than
noticed by accident. Product framing: the app exists for the moment a Korean immigrant
knows the words but not the socially right way to say them, so the act axis matters as
much as the setting axis.

Axis A, life domain (`domain` field), 14 values:

| Code | Setting | Typical interlocutor |
|---|---|---|
| housing | landlord, leasing office, repair, HOA, roommate | landlord, property manager |
| finance | bank, insurance, taxes, scam calls | teller, agent, phone banking |
| government | SSA, DMV, USCIS, city hall, court | clerk, officer |
| healthcare | clinic, hospital, pharmacy, insurer | receptionist, nurse, doctor, pharmacist |
| school | children's school, childcare, PTA | teacher, front office, other parents |
| workplace | coworkers, manager, HR, clients | boss, peer, HR |
| job_search | interview, networking, references | recruiter, interviewer |
| shopping | retail, returns, repairs, phone store | cashier, clerk, contractor |
| dining | restaurants, cafes, tipping, delivery | server, host, driver |
| neighbors | neighbors, park, gym, invitations | neighbor, acquaintance |
| driving_emergency | traffic stop, accident, 911 | police, dispatcher, other driver |
| remote_support | phone, IVR, chat support, email to institutions | call-center agent, bot |
| community | church, volunteering, Korean-adjacent English settings | pastor, coordinator |
| friendship | friends, dating, partner's family, hosting | friend, date, host |

Axis B, pragmatic act (`act` field), 13 values, each paired with the Korean-speaker
failure mode it is meant to catch:

| Code | Act | Known failure mode for Korean speakers |
|---|---|---|
| request | ask a favor or service | over-direct with peers, over-apologetic with superiors, apology where thanks is expected |
| refuse | decline, say no | vague excuse or "maybe" that is heard as yes |
| complain | report a problem, escalate | under-assertive, gives up after one attempt |
| apologize | take responsibility | "I'm sorry" overuse, apology in place of thanks |
| small_talk | greetings, ritual questions | literal answer to "How are you?", no follow-up question |
| assert | disagree, push back, state an opinion | silence in meetings, never challenging a superior or teacher |
| clarify | ask to repeat, slow down, interpreter | says "Okay" and stays lost |
| bad_news | give or receive bad news, express concern | restraint read as indifference |
| negotiate | lease, price, salary, bill dispute | rarely attempted at all |
| compliment | give or respond to a compliment | denial instead of "thank you" |
| emotion | gratitude, sympathy, feelings | under-expression, stiff honorific carry-over |
| written | email, text, note, form | register errors, Korean letter structure copied into English |
| social_norm | tipping, first names vs titles, personal questions | over-personal questions, title where first name is expected |

Axis C, input shape (`input_mode` field), 4 values. All 22 original cases were `say_it`;
the other three shapes are what real users also type and were absent until 2026-09:

- `say_it`: "what should I say" before the situation
- `check_it`: "I said X, was that okay?" after the situation. Must include cases where the
  user's draft was fine, so the set measures false positives (the model inventing a
  problem) as well as misses
- `fix_it`: "here is my sentence or email, make it natural"
- `robustness`: English-only input, Korean-English mixed input, input near the 500-char
  cap, very short vague input. Output must still be valid 3-variant JSON

Priority cells. Eight cells carry the most evidence of real harm (health, money, legal
status, a child's outcome) and each holds at least 3 cases, including one `check_it`
where the draft was acceptable:

1. healthcare x clarify (interpreter, repeat dosage)
2. healthcare x request / complain (appointments, symptoms, insurance denial)
3. remote_support x clarify / complain (phone holds, escalation)
4. housing x complain / negotiate / written (repair, rent, written notice)
5. workplace x assert / refuse (pushing back on a manager, declining extra work)
6. school x assert / request / written (disagreeing with a teacher, email)
7. government or driving_emergency x clarify / request (interpreter, traffic stop, 911)
8. neighbors or friendship x small_talk / compliment / social_norm

Coverage rule: every domain has at least 2 cases, every act at least 2, every priority
cell at least 3. Firm and refusal-family `tone_intent` values must not be a minority,
because the product's core pain is under-assertion, not over-assertion.

Taxonomy sources, verified 2026-09-05: USCIS "Welcome to the United States" (M-618)
chapter list https://www.uscis.gov/sites/default/files/document/guides/M-618.pdf ;
CASAS adult ESL competencies
https://www.casas.org/docs/default-source/training-materials/casas-competencies.pdf ;
KACF and Asian American Federation "Toward Better Aging" (language as the primary
healthcare barrier for Korean seniors) https://kacfny.org/toward-better-aging/ ;
Korean-parent and school communication, Lim 2012
https://files.eric.ed.gov/fulltext/EJ974687.pdf ; Korean and English politeness maxims
https://koreatesol.org/content/differences-maxims-politeness-conventions-and-directness-speech-between-korean-and-english .
Deprioritized for now: community (church), plain restaurant ordering, job interview,
and bad_news, each kept at the 2-case floor.

### Test case structure

Cases live in `eval/s07-analysis/test_cases.json`. Each case is a JSON object:

```json
{
  "id": "s07_001",
  "input_text": "친구한테 서운한 마음을 정중하게 표현하고 싶어요",
  "tone_intent": "정중한",
  "domain": "friendship",
  "act": "emotion",
  "input_mode": "say_it",
  "expected_behaviors": [
    "All 3 variants are grammatically correct American English",
    "Variant 1 matches the requested polite tone",
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
- `domain`, `act`, `input_mode`: the three taxonomy axes above; every value must be one of the listed codes (replaced the free-form `category` field on 2026-09-05)
- `expected_behaviors`: positive criteria the judge looks for
- `expected_failure_modes`: negative criteria; if observed, that dimension drops
- `draft_quality` (check_it only, required there): `good` when the English draft quoted in
  the input was already natural and appropriate, `flawed` when it had a real problem. The
  runner uses it to count false alarms and missed flaws (see Scoring).
- `pair_of` (optional): the id of an explicit-tone original this case twins. See
  "Implicit-cue pairs".

Two conventions for writing criteria, both weaker claims than they first appear.

Point at what the judge can see. The `s07_analysis_v1` output is three expressions with
tips and no verdict field, so a criterion phrased as "Output confirms the draft was fine"
does not say which field carries that confirmation. Prefer "variant 1 keeps the draft
essentially as-is" or "the cultural tip says the draft was appropriate". This is a
readability convention, not a correctness fix: on 2026-09-06 the judge was given the older
"Output recognizes ..." phrasing for s07_024 and graded it without difficulty (4.75 and
4.50 over two runs), citing the clause "variant 1 keeps it essentially as-is" as its
evidence. No measured improvement is claimed for the rewrite.

Do not require facts the input does not give. A date, an account number, or a reason that
appears nowhere in `input_text` must not be demanded of the answer; a placeholder such as
`[date]` is the correct handling. This one has a concrete failure behind it: s07_065 asked
for a reason the input never supplied while s07_062 forbade inventing facts, so the set
contradicted itself. It follows Anthropic's Step 2, write unambiguous tasks with reference
solutions (verified 2026-09-06,
https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents ).

#### Implicit-cue pairs

Most cases state the tone the user wants ("정중하지만 물러서지 않게"). Real users often
describe only the situation. Nasim et al. (arXiv 2604.17718, section 3.2 "Triad
Evaluation Design", verified 2026-09-06, https://arxiv.org/html/2604.17718 ) measured
the same scenarios with and without an explicit cultural instruction and found models
recover only about one fifth of their instructed behaviour from situational cues alone
(mean PCS 0.196, section 4.1). PhraseLog's prompt is meant to close exactly that gap, so
the set needs to measure it.

A twin copies an original's situation, relationship cues, domain, act, input_mode, and
criteria, removes the tone request from the input, sets `tone_intent` to null, and adds
one criterion: variant 1 must still land on the original's tone. Eight twins exist, one
per priority cell (s07_075 to s07_082). The runner reports the per-pair `tone_match`
difference as `pair_deltas`; a negative delta means the prompt needs to be told. This is a
measurement of the prompt, not a fix; the fix belongs in `prompts/s07/v2.md`.

Cases are added by the owner manually for now. When user data accumulates (W13+), real anonymized inputs become candidates for the case pool.

### Judge prompt

Lives in `eval/s07-analysis/judge_prompt.md`, versioned alongside test cases.

Judge model: Anthropic Claude, and it must be a different model from the one generating the S07 output. Anthropic's testing guide gives that as best practice without stating why (source https://platform.claude.com/docs/en/test-and-evaluate/develop-tests , verified 2026-09-09); the measured reason is that same-family generator and judge pairs inflate scores by 8.9 percent against 23.6 percent for the same model (Preference Leakage, https://arxiv.org/abs/2502.01534 , verified 2026-09-09), which the current Sonnet generator and Opus judge pairing does not escape. From 2026-06 to 2026-09 the generator and judge were both Sonnet; that is recorded as a known gap, and baselines from that period are not comparable with later ones. The concrete model ids live in `eval/s07-analysis/run_eval.py` and `AI_PIPELINE.md`. Quality of judging matters more than speed, so the judge is never the cheapest tier.

The judge receives:
1. The system prompt explaining the rubric
2. The test case (input, tone_intent, expected behaviors, expected failure modes, and
   since judge-v3 the taxonomy codes, `draft_quality`, and `pair_of`)
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
  "failure_modes_observed": [],
  "draft_handling": "n/a"
}
```

Each rationale must be one or two sentences. The `failure_modes_observed` array references entries from `expected_failure_modes` by partial string match. `draft_handling` (judge-v3) is `kept` when variant 1 is the user's quoted draft with at most a few words polished, `rewritten` when it materially changed, and `n/a` outside check_it.

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

**Reported, not gated** (written into the run artifact since 2026-09-06; thresholds are
set only after two baselines exist, so the numbers come from data rather than guesses):
- `by_input_mode`: the four dimension averages per input_mode, so a check_it collapse is
  visible instead of averaged away by say_it.
- `draft_summary`: `false_alarms` (a `good` draft whose variant 1 was `rewritten`) and
  `missed_flaws` (a `flawed` draft whose variant 1 was `kept`), counted per trial. The
  split follows Lin, Ngo, and Chen 2026, Methodology, Data Analysis: a correct segment
  flagged is a false alarm, a correct segment left alone is an accurate non-intervention
  (verified 2026-09-06, https://link.springer.com/article/10.1007/s42321-026-00236-4 ).
  The product reason is principle 1 in `PROJECT_CONTEXT.md`: rewriting a sentence the user
  already said well adds shame without adding skill.
- `pair_deltas`: per implicit-cue twin, `tone_match` of the twin minus the original.

The CI workflow `.github/workflows/eval.yml` computes these comparisons and posts the diff as a PR comment.

### Running locally

```
python eval/s07-analysis/run_eval.py
```

Reads `eval/s07-analysis/test_cases.json`, runs S07 against each case (using the prompt under `prompts/s07/v{current}.md`), feeds each output through the judge, writes results to `eval/runs/{timestamp}_{git_sha}.json`.

Cost per full run: cases x trials (default 3) x 2 calls (generate + judge). With 74 cases that is 444 calls per run. The dollar figure is not estimated here; the runner sums actual token usage at the `AI_PIPELINE.md` verified rates and writes it into the run artifact as `estimated_cost_usd`, which is the only number to quote.

### CI integration

`.github/workflows/eval.yml` triggers on:
- PRs that touch `prompts/s07/**`
- PRs that touch `eval/s07-analysis/**`
- PRs that touch backend code in the analysis service

The workflow:
1. Runs the no-API unit tests for the gate logic (`eval/s07-analysis/test_*.py`)
2. Picks the newest committed run artifact under `eval/runs/` on the default branch as the baseline. `main` is not re-run; baselines are promoted deliberately, see `eval/runs/README.md`
3. Runs the eval against the PR branch with `--baseline` pointing at that artifact
4. Posts the runner output as a PR comment and sets CI status pass/fail per the criteria above

If the baseline's case-id set differs from the current `test_cases.json`, the runner reports `stale_baseline` and exits 1 instead of comparing means over two different populations. The fix is to run the eval on the merged prompt and promote the new artifact.

### Timeline

| Week | Tier 1 activity |
|------|----------------|
| W1–3 | Build initial 10–20 cases. First judge prompt v1. First run script. |
| W4–8 | Run on every prompt or analysis-code PR. Iterate prompt v2, v3, etc. |
| W9–12 | Stabilize. 2026-09-05: taxonomy fixed, cases 22 to 74, judge model separated from generator. |
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
2. ~~**Tier 1 case count for v1 launch**~~ — Decided 2026-09-05: size follows the scenario coverage taxonomy (74 at adoption), not a fixed number. Further growth comes only from real-input failures.
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
- `docs/exec-plans/2026-09-06-eval-case-criteria-revision.md` — why the criteria were
  rewritten, the twins added, and the report-only metrics introduced

### References (all verified 2026-09-06)

- Anthropic, "Demystifying evals for AI agents", 2026-01. Step 2 (reference solutions),
  Step 3 (balanced problem sets).
  https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents
- Nasim et al., "Do LLMs Use Cultural Knowledge Without Being Told? A Multilingual
  Evaluation of Implicit Pragmatic Adaptation", arXiv 2604.17718, 2026-04. Section 3.2
  triad design, 4.1 PCS, 4.2 authority cues transfer best. Korean was not tested; used as
  a design reference, not as a Korean measurement. https://arxiv.org/abs/2604.17718
- Lin, Ngo, and Chen, "Comparative Analysis of LLM-Based Writing Tools for Error
  Correction and Feedback", English Teaching and Learning, 2026-06-08. Data Analysis:
  false alarm vs accurate non-intervention. A grammar-correction study; only the
  classification is borrowed. https://link.springer.com/article/10.1007/s42321-026-00236-4
- Park and Trisnadi, "The transition of legal status among Korean immigrants in the United
  States", Journal of Migration and Health, 2025-09-20. Sections 3.4.2.1 (respect toward
  elders) and 3.4.2.2 (friendly strangers) describe felt differences, not rules; cultural
  criteria are worded as safe defaults, not absolutes, for that reason.
  https://pmc.ncbi.nlm.nih.gov/articles/PMC12508842/
- Indeed Engineering, "Bootstrap Confidence Intervals for LLM Evaluation", 2026-07-08.
  Assumptions: inputs must be an iid sample from the target distribution.
  https://engineering.indeedblog.com/blog/2026/07/bootstrap-confidence-intervals-for-llm-evaluation/
- `eval/s07-analysis/run_eval.py` — runner script
- `prompts/s07/v{N}.md` — prompts being evaluated

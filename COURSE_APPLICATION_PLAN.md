# Course → PhraseLog application plan

Maps every module of Anthropic's **Building with the Claude API** course to a concrete
PhraseLog deliverable, and says **when** to build it. Grounded in the existing specs
(AI_PIPELINE.md, EVAL_PLAN.md, ADR-001/003/004/009) — this plan creates the *artifacts*
those specs already reference but had not yet produced.

Three buckets: **before W4 (build now)**, **during W4–12 (build while coding)**,
**W13+ operate (build after launch)**.

---

## A. Before development (build now — W1–3)

These map to the course modules that gate everything else: *System prompts*,
*Prompt engineering*, *Structured data*, *Prompt evaluation*. They are also W1–3
Definition of Done per ADR-003/ADR-004.

| Course module | PhraseLog artifact (created in this set) | Why now |
|---|---|---|
| System prompts | `prompts/s07/v1.md` | S07 is the product's foundation (PRD 5.3). Must exist before any analysis code. |
| System prompts | `prompts/roleplay/{init,turn,feedback,result}/v1.md` + `coaches/{mia,david,sarah}.md` | S12 needs these the moment roleplay coding starts (W4+). |
| Prompt engineering (clear/direct, specific, XML, examples) | Same prompt files — written with role framing, explicit rules, XML-tagged sections, one full few-shot example | The course's techniques are the difference between a vague prompt and a testable one. |
| Structured data (JSON-only, schema) | Output schema enforced in prompts + `validate_schema()` in the runner | AI_PIPELINE.md requires JSON-only + backend schema validation on every call. |
| Prompt evaluation (workflow, test datasets) | `eval/s07-analysis/test_cases.json` (12 cases) | EVAL_PLAN Tier 1 needs a regression signal before W4 prompt iteration. |
| Model-based grading | `eval/s07-analysis/judge_prompt.md` | LLM-as-judge with 4-dim Likert rubric (EVAL_PLAN). |
| Code-based grading | `validate_schema()` in `run_eval.py` | Catches malformed output deterministically before the judge runs. |
| Running the eval | `eval/s07-analysis/run_eval.py` + `.github/workflows/eval.yml` | Local run + PR regression gate. Implements ADR-009 N=3 trials. |

**Status: created in this starter set.** Next manual steps for you:
1. Read each prompt against your native-validator network; tune wording.
2. Run `run_eval.py` once you have an API key to produce the first baseline artifact.
3. Commit that baseline so the CI regression gate has something to compare against.

---

## B. During development (build while coding — W4–12)

Built into features as they land, not preemptively.

| Course module | PhraseLog deliverable | Trigger / week |
|---|---|---|
| Multi-turn conversations | Roleplay turn loop passing full transcript to `roleplay_turn_response` | W4–8, when S12 is built |
| Tool use (functions, schemas) | Not needed for v1's core loop. Only if "save to library" or "schedule review" becomes an in-conversation action. Defer unless a feature demands it. | As-needed, likely v1.1+ |
| Response streaming | S07 result streaming for perceived latency (optional UX polish) | W9–11 polish, only if p95 latency feels slow |
| Prompt iteration (v2, v3…) | New `prompts/s07/v2.md` etc., each gated by the eval | W4–8, every prompt change |
| Temperature / output control | Tune generation temperature for S07 (lower for consistency) | W4–8, as an eval variable |
| Schema validation + retry | Backend implements the "retry once with constraint reminder" path from AI_PIPELINE.md | W4–8, in the analysis + roleplay services |

**Discipline:** every prompt change bumps `prompt_version`, runs the eval, and only
merges if the regression gate passes. This is the course's eval workflow operationalized.

---

## C. After launch (operate — W13+)

| Course module | PhraseLog deliverable | Trigger / week |
|---|---|---|
| Prompt evaluation at scale | Tier 2 S12 roleplay eval (`eval/s12-roleplay/`) | After ~50 real sessions, ~W13–16 (ADR-003) |
| Model-based grading (validated) | Tier 3 golden dataset: native-validator labels vs judge scores | W17+, after model comparison (EVAL_PLAN Tier 3) |
| Extended thinking / model comparison | Model Comparison phase: Sonnet vs successor, Haiku routing review | W17–20 (ADR-001 says routing is re-evaluated here) |
| Prompt caching | Cache the S07 system prompt to cut input cost (~50–90% on cached tokens) | W17+ — AI_PIPELINE Open Question 4 defers it here on purpose |
| RAG and Agentic Search (chunking, embeddings, BM25, multi-index) | RAG over the user's saved-expression library, or a cultural-context corpus, on pgvector | W21–24 (ADR-004 roadmap). The course's RAG module is the direct reference. |
| MCP servers/clients | Connect Claude Code to GitHub issues / `ai_request_logs` / Notion for the operate-phase harness | Post-launch dev-workflow improvement, not product |
| Code execution / Files API | Not product-relevant for v1; skip unless a feature needs it | — |

---

## What this plan deliberately does NOT build yet

- **Tool use / agents-and-workflows** for the product: v1's loop is request→response,
  not an autonomous agent. The course covers it; PhraseLog v1 does not need it. Revisit
  only if a concrete feature requires an in-conversation action.
- **RAG before W21:** building retrieval before there is a corpus and real usage would
  optimize for guesses. The course module is the reference; the timing is fixed.
- **Prompt caching before W17:** premature; measure real cost from `ai_request_logs`
  first, then decide.

The principle (matches your "검증 우선, 빌드 후"): build the prompt + eval foundation now
because prompt quality drives all perceived value and needs a regression signal; defer
retrieval, caching, and agentic features until real data justifies them.

---

## File map of this starter set

```
prompts/
  s07/v1.md                      # S07 analysis system prompt
  roleplay/
    init/v1.md                   # session setup (planned_turns, opening line)
    turn/v1.md                   # per-turn coach reply
    feedback/v1.md               # Haiku per-turn language check
    result/v1.md                 # session debrief
    coaches/{mia,david,sarah}.md # persona blocks injected into the above
eval/s07-analysis/
  test_cases.json                # 12 starter cases
  judge_prompt.md                # LLM-as-judge rubric (4-dim Likert)
  run_eval.py                    # generate + code-grade + model-grade, N=3 trials
.github/workflows/eval.yml       # PR regression gate
COURSE_APPLICATION_PLAN.md       # this file
```

## Known reconciliations for you to confirm (flagged honestly)

- **Coach prompt path:** AI_PIPELINE.md routing table uses `prompts/roleplay/{stage}/v{N}.md`,
  while `coach_profiles.prompt_template_ref` example shows `prompts/roleplay/mia/v1.md`.
  This set uses stage-based files + a separate injectable `coaches/{slug}.md` persona
  block. Decide which convention is canonical and update the other doc to match.
- **Coach personas** here are minimal v1 drafts. Replace with the real
  `coach_profiles.persona_summary` seed text once finalized.
- **Eval judge model freeze:** EVAL_PLAN Open Question 1 — decide whether to freeze the
  judge model for trend stability before you commit the first baseline.

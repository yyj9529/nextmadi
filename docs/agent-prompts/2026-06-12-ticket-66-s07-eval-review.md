# Ticket #66 S07 Eval Case Review Prompts

## Fable5 Prompt

Use this when asking Fable5 to review the quality of the S07 eval cases.

```text
You are reviewing PhraseLog issue #66: S07 eval case quality review.

Product context:
- PhraseLog is for Korean immigrants in the US.
- Core promise: "Turn what I couldn't say today into something I can say next time."
- It is not real-time translation, generic grammar learning, or a free-form chatbot.

Read these files:
- PROJECT_CONTEXT.md
- docs/EVAL_PLAN.md
- docs/AI_PIPELINE.md, especially s07_analysis_v1
- eval/s07-analysis/test_cases.json

Task:
Review whether the S07 eval cases are good quality evaluation cases for PhraseLog.
Focus on wording, tone intent, category coverage, expected behaviors, expected failure modes, and whether the cases reflect the product philosophy.

Do not edit files.
Do not propose generic edtech cases unless they match the Korean-immigrant use case.
Do not include raw private user data or new real-user text.

Output:
1. Overall verdict: pass / changes recommended / changes required.
2. Top findings, ordered by severity.
3. Case-specific notes using case IDs only, not full raw input text.
4. Missing coverage, if any.
5. A concise issue #66 comment draft.
```

## Codex Prompt

Use this when asking Codex to do the repo-side checks and final issue/comment packaging.

```text
Process only GitHub issue #66: S07 eval case review.

Follow AGENTS.md and CLAUDE.md.
Do not implement product code.
Do not edit eval/s07-analysis/test_cases.json unless I explicitly ask.
Do not run live eval unless I explicitly approve Anthropic/API cost.

First run only the local cheap command:

powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1

If GitHub CLI is unavailable, rerun with:

powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1 -SkipIssueLookup

Then combine:
- the command output,
- the Fable5 review findings,
- docs/EVAL_PLAN.md acceptance expectations,
- docs/AI_PIPELINE.md s07_analysis_v1 constraints.

Output either:
- a concise GitHub issue #66 comment draft, or
- docs/reviews/YYYY-MM-DD-s07-eval-cases-review.md if the review is long.

Keep raw case input text out of the final summary. Refer to cases by ID.
```

## Manual Commands

Cheap validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Test-S07EvalCases.ps1
```

Ticket context plus cheap validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1
```

Gated live eval, approval required:

```powershell
python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1
```

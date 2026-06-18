---
description: Process GitHub issue #66, S07 eval case review
argument-hint: [SKIP_ISSUE_LOOKUP=true]
---

Process only GitHub issue #66: S07 eval case review.

Rules:
- Read `CLAUDE.md`, `SECURITY.md`, `docs/EVAL_PLAN.md`, `docs/AI_PIPELINE.md`, and `docs/ai-ticket-operating-map.md` first.
- Do not edit `eval/s07-analysis/test_cases.json` unless the owner explicitly asks for edits.
- Start with cheap repo checks only.
- Do not run live eval unless the owner explicitly approves Anthropic/API cost.
- If review findings are substantial, draft `docs/reviews/YYYY-MM-DD-s07-eval-cases-review.md`; otherwise draft an issue comment.

Run the local command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1
```

If issue lookup should be skipped:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1 -SkipIssueLookup
```

Then use the Fable5 review prompt from `docs/agent-prompts/2026-06-12-ticket-66-s07-eval-review.md`.

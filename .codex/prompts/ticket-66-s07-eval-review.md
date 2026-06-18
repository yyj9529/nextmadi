---
description: Process #66 S07 eval case review
argument-hint: [SKIP_ISSUE_LOOKUP=true]
---

Use this repo prompt card for #66. Project-local Codex hooks live in `.codex/hooks.json`; review/trust them with `/hooks` in Codex before relying on them.

Task:
- Process only GitHub issue #66, S07 eval case review.
- Do not edit eval cases unless I explicitly ask.
- Run only cheap checks first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1
```

If `gh` is unavailable or issue lookup is not needed:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tickets\Invoke-Ticket66S07EvalReview.ps1 -SkipIssueLookup
```

Do not run this without explicit approval:

```powershell
python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1
```

Expected output:
- JSON validity and category count from Codex.
- Fable5 quality findings from the prompt in `docs/agent-prompts/2026-06-12-ticket-66-s07-eval-review.md`.
- Draft issue #66 comment or `docs/reviews/YYYY-MM-DD-s07-eval-cases-review.md`.

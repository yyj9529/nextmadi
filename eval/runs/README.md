# Eval run artifacts

Local eval runs are ignored by default because run artifacts may contain model output
or evaluation rationale. Commit only reviewed baseline artifacts that are safe for the
public repo and needed by CI regression checks.

Baseline promotion checklist:

1. Confirm the artifact contains no secrets or raw identifiable user text.
2. Confirm `git_sha`, prompt version, generator model, judge model, and trial count.
3. If the runner changed, generate the baseline from the committed runner version or
   call out the runner change clearly in the baseline commit message.
4. Promote the baseline intentionally:

```bash
git add -f eval/runs/<artifact>.json
```

CI reads the latest committed JSON artifact from the repository default branch.

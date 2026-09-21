# Exec plan: S07 evaluation failure recovery

## Goal

82케이스 × 3회 평가에서 실패한 원인을 분리하고, 기존 평가 기준을 유지한 채
새 S07 프롬프트 후보를 검증한다. 좋은 영어 초안을 불필요하게 고쳐 사용자의
자신감을 떨어뜨리는 동작과 JSON 출력 실패를 우선 해결한다.

## Source specs

- `docs/EVAL_PLAN.md`: Tier 1 scoring, fixed judge-v3, draft and pair reports.
- `docs/quality-gates.md`: S07 schema, quality and regression gates.
- `docs/screens/s07.md`: three variants and first-variant tone.
- `docs/AI_PIPELINE.md`: S07 output schema and versioned prompts.
- PR #160: case-set comparison guard, still open when checked 2026-09-16.
- Issue #69: launch checklist; this task addresses only S07 evaluation readiness.

## Baseline evidence

Local artifact `eval/runs/20260908T233826Z_6f3104a.json`: 82 cases, three trials,
aggregate 4.199, failed, five catastrophic cases. Recorded estimated cost $16.5246;
not a current price quote and potentially undercounted when judge parsing failed.

- s07_002: one judge JSON parse failure; two successful trials.
- s07_029: one generation JSON parse failure; two successful trials.
- s07_054: three generation JSON parse failures.
- s07_058: three generation JSON parse failures (extra data after JSON).
- s07_042: three valid judgments with tone_match=1; unnecessary rewriting of an
  appropriate firm refusal. v1 always asks for three new expressions and never
  explicitly distinguishes checking a good draft from repairing a flawed one.

The artifact does not retain generated responses. Prose, refusal, truncation and
invalid quoting cannot all be distinguished retrospectively. Do not call any of
those an established cause without fresh evidence. Exception fallback scores of
1 mix eight execution failures with real quality judgments.

## Files expected to change

- New `prompts/s07/v2.md`: candidate; preserve v1 and runtime selection.
- `eval/s07-analysis/run_eval.py` and focused tests: safe diagnostics and explicit
  failure gating; narrow case selection for debugging; preserve scoring thresholds.
- This execution plan: evidence, commands, outcomes and remaining review gates.

## Acceptance criteria

- Full 82 × 3 candidate run: aggregate >=4.0, dimensions >=3.5, no trial dimension
  below 2.0, no execution or schema failures.
- Compare against the same 82 IDs, generator and judge; no threshold relaxation,
  case removal, selective replacement of trials, or re-labeling good drafts.
- Report false alarms, missed flaws and implicit-tone pairs alongside the average.
- Offline regression tests pass. Independent review remains required before merge.

## Test plan

1. Recompute saved baseline and distinguish execution failures from scored failures.
2. Test failure diagnostics and schema gating with mocked calls before implementation.
3. Run the known failures first, then all 82 cases × 3 with the frozen judge.
4. Preserve every experiment; partial runs are never full-suite passes.

## Risk areas

Paid evaluation, unavailable credentials/dependencies, non-deterministic model output,
and a dirty shared checkout. Existing unrelated edits are preserved. Do not activate
the candidate in production or change models/retry counts to obtain a passing score.
No real user input or provider response bodies go into diagnostic artifacts.

## Decision log

- Use repository files for durable task context; attached diagrams are workflow
  references, not additional permissions or project specifications.
- Implement within the user's requested quality-recovery scope. PR #160's broader
  baseline guard remains separate; verify comparison inputs explicitly here.
- Investigate JSON errors without accepting arbitrary prose as valid schema output.

## Verification and resumption

Offline command (bundled Python, no packages or API required):

```powershell
& 'C:/Users/ywj95/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -X utf8 -m unittest discover -s eval/s07-analysis -p 'test_*.py' -v
```

Result: **25 passed, 0 failed, 0 skipped**. The initial eight regression tests
failed before the fixes (13 subtest failures and three errors). Added three CLI
tests then caught a test-fixture path mismatch; the temporary output directory
now stays under the repository's eval/runs path, matching the runner contract.

`git diff 6f3104a -- eval/s07-analysis/test_cases.json
eval/s07-analysis/judge_prompt.md prompts/s07/v1.md` showed no differences. This
verifies the local case set, judge and v1 against the recorded baseline commit.
The baseline lacks content hashes; new runs record prompt, judge and case hashes.

After dependency installation approval, use an isolated temporary Python target
for the existing requirements, then run (with that environment's Python):

```powershell
python -X utf8 eval/s07-analysis/run_eval.py --prompt-version v2 --trials 3 --case-ids s07_002 s07_029 s07_042 s07_054 s07_058
python -X utf8 eval/s07-analysis/run_eval.py --prompt-version v2 --trials 3 --baseline eval/runs/20260908T233826Z_6f3104a.json
```

Run the full command only after inspecting the diagnostic subset. Every run is a
new artifact; never replace failed trials with successful retries. The subset
has `scope=subset` and `full_suite_passed=false` even if its cases pass. Existing
one-point execution penalties remain for backward comparison, now explicitly
marked `score_source=failure_penalty`; invalid trials also block the gate directly.

## Final outcome

**Implementation candidate ready; AI quality gate pending.** No new paid evaluation
has run and no new passing AI result is claimed. New v2 is not selected by the
backend or CI defaults. Independent review and runtime promotion remain pending.

The available bundled Python lacks anthropic and python-dotenv; the Windows Python
launcher reports no installed Python. A nonempty API-key entry exists in .env,
but its validity has not been tested and its value was never displayed. The owner
has been asked to approve installing the existing requirements into a temporary
environment, as required by SECURITY.md. Await that response before installation.

## What changed after execution

Shell output required PTY mode; non-PTY reads returned no visible output. The default
Python command stalled; the bundled executable works but lacks evaluation packages.
GitHub CLI configuration was inaccessible in the sandbox; the read-only GitHub
connector successfully retrieved PR #160 and issue #69.

The initial "five catastrophic cases" description mixed two failure classes:
four cases contain execution errors (eight trials total), while s07_042 contains
three actual low-tone judgments. The runner now preserves failed judge-call cost,
records safe stage/stop diagnostics, rejects non-string schema fields and invalid
numeric scores, and prevents schema/execution failures from passing on high scores.
Model selection, retry counts, case criteria and judge-v3 were not changed.

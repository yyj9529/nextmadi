# S07 next paid step — approval pending

This is a proposal, not authorization. No paid request has been sent.

## First step: judge calibration only

- Input: the 13 fixed synthetic fixtures in `eval/s07-analysis/judge_fixtures.json`.
- Cases: good/bad pairs for invented heater history, invented future help, appropriate original, mind-reading, necessary clarification, unnecessary clarification; one judge-injection bad answer.
- Model: configured `claude-opus-5`; generation calls 0, judge calls at most 13, automatic retries 0. Account access has not been checked by calling the model.
- Output cap: 4,000 tokens/call. Global standard input/output prices: $5/$25 per million tokens, verified 2026-09-19 at [official pricing](https://platform.claude.com/docs/en/about-claude/pricing). Generation model `claude-sonnet-4-6` remains unchanged ($3/$15), but this stage does not call it.
- Estimated expected cost: approximately $0.50–$1.00, assuming roughly 1,000–2,000 output tokens per judgment and ordinary tokenization of the fixed payloads; unmeasured and subject to verification. The exact current payload byte counts plus 1,024-token overhead and full output caps give a conservative total reservation of $1.694145. Proposed hard API-usage cap: **$2**, maximum **13 calls**, whichever stops first. Taxes/currency conversion are outside this API token estimate.
- Stop immediately on the first false approval/rejection, uncertain judgment, schema/transport error, or insufficient next-call budget. Preserve all previous outputs and actual/unknown costs. Do not silently retry for a better judgment.
- This approval would not authorize generation, repeated subset, full suite, deployment, or a model change. Those require a separate proposal after the result is inspected.

## Environment prerequisite

The bundled Python interpreter can run all offline tests but lacks the already-declared `anthropic` SDK. Reuse an existing evaluation environment if available; otherwise installing the repository's existing `eval/s07-analysis/requirements.txt` into an isolated environment needs approval. No new requirement or dependency version change is proposed. API credentials must come from the approved environment and must never be printed.

## Subsequent generation proposal (not included in this approval)

Subset IDs: `s07_029`, `s07_042`, `s07_054`, `s07_058`, `s07_083`–`s07_090`. One trial each, at most 24 generation calls (one schema retry per case) plus 12 judge calls. A concrete cost/cap proposal follows calibration, before execution. Repeat/full stages remain blocked on passing prerequisites and separate approval.

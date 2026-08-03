# ADR-011: Per-Attempt Logging in ai_request_logs

Date: 2026-08-02
Status: Accepted

## Context

`AI_PIPELINE.md` held two contradictory rules. The logging contract
said every external API call writes exactly one row to `ai_request_logs`, with
`latency_ms` measured "including retries". The fallback policy retried schema
failures, 5xx, and network errors once each, and 429 twice. Every such retry is a
separately billed request, folded into that single row.

Three failures followed. Cost was under-counted: on success after a retry only
the final attempt's tokens were recorded, and on terminal failure the writer
stored null tokens and null cost for calls billed twice. Retry rate was
unmeasurable in SQL, leaving the post-launch metric "JSON schema failure rate"
(PRD 3.2) with no query behind it. The alert on `status = 'error'` exceeding five
percent could not see a failure a retry had recovered, so it measured consecutive
failure, not failure.

Two smaller defects shared the root: `error_code` kept only the last attempt,
discarding the first error of a mixed sequence, and `latency_ms` counted backoff
sleep — up to four seconds on the 429 path — as model latency.

## Decision

`ai_request_logs` stores one row per attempt, not per logical call. Three columns
carry the grain: `attempt_number` (1-based), `is_final_attempt` (true once per
group), and `attempt_group_id`. Grouping is two levels —
`request_correlation_id` for a user action, `attempt_group_id` for one logical
call within it.

Queries meaning "how many requests" filter on `is_final_attempt`. Cost queries
apply no attempt filter at all, because retries are billed. Each attempt carries
its own `error_code`, tokens, latency, and cost; backoff falls between rows.

Unknown and zero are separated. A null cost means no usage was reported, so sums
are a lower bound; only `cache_hit` rows carry a known zero, matching existing
behavior. Timeout tokens are not estimated from prompt length — an unverifiable
number in a cost column devalues every verifiable one.

## Consequences

Cost becomes correct without a filter and retry rate becomes a SQL query. The
error rate alert splits: final-attempt failure for user impact at the existing
five percent, all-attempt failure as a degradation warning whose threshold stays
TBD until a W4-8 baseline exists.

Row growth is bounded. Retries occur only on the Claude path; the STT and TTS
services contain no retry code. Assuming LLM calls are half of rows and a five
percent retry rate, rows grow about 2.5 percent — both figures estimated, pending
verification against W4-8 data.

One query breaks: `findLogIdByCorrelation` must now select the final attempt
rather than any single row for the correlation id.

Rollback is asymmetric, and the boundary is the first production deploy. Before
it, V008 is additive over a deterministic backfill and reverses cleanly: roll the
backend back, then drop `idx_logs_attempt_group`,
`chk_ai_request_logs_attempt_number`, and the three columns. After production
traffic has written per-attempt rows, that same drop destroys the attempt grain —
retry rows survive, but which attempt belonged to which call is gone and cost sums
double-count. Past that boundary, roll the application back and leave the schema;
an older backend ignores the columns. No compensating migration is written in
advance, because having one invites running it after it turns destructive.

This ADR does not supersede ADR-001. It changes what the pipeline records, not
which services it calls or how it retries them; the fallback matrix is unchanged.

## Alternatives Considered

- **Summary columns on one row** (`attempt_count`, `first_error_code`,
  `total_input_tokens`, `total_output_tokens`). Avoids row growth, breaks no
  query. Rejected: fixes cost but not per-attempt latency, error sequence, or
  backoff contamination, and `input_tokens` alongside `total_input_tokens`
  reproduces the original ambiguity as a naming problem.
- **A column marking that a retry carried the constraint reminder.** Rejected as
  derivable from `attempt_number` and the preceding attempt's `error_code`.

## Related

- `docs/AI_PIPELINE.md` - logging contract and fallback recording table.
- `docs/data-model.md` - `ai_request_logs` schema and indexes.
- `docs/architecture.md` - the two failure-rate alerts.
- `docs/exec-plans/2026-08-02-ai-request-logs-attempt-level.md` - option analysis.
- ADR-001 - the pipeline whose calls this table records; unchanged by this ADR.

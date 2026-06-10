# SECURITY.md

Short, always-loaded security rules for AI agents working on PhraseLog. The full
architecture and secrets handling live in `docs/architecture.md`; this file exists
so an agent does not have to read that long document to know the boundaries. When
this file and `docs/architecture.md` disagree, `docs/architecture.md` wins and this
file must be corrected.

## Forbidden (never, without exception)

- Print, log, or commit secrets. Secrets live in AWS Secrets Manager (Anthropic API
  key, OpenAI API key, JWT signing secret, DB credentials, OAuth client secrets)
  and Vercel env vars (including Amazon SES SMTP credentials and sender address)
  — never in source or committed `.env`.
- Log raw user conversation text, or identifiable family/medical/school/financial
  context, into any artifact that can reach the public repo (`eval/runs/`, commits,
  PR descriptions, `docs/`).
- Modify the production database directly, or run destructive SQL, without explicit
  owner approval.
- Disable, weaken, or bypass an auth check to make a test pass.
- Use skip-permissions ("YOLO") mode as a default. See "Permission posture" below.

## Approval required (stop and ask the owner)

- DB schema migration.
- Deploy to Vercel or EC2.
- Dependency install or version bump.
- Any change to OAuth / NextAuth / JWT handling or email magic-link delivery provider.
- Changes that affect AI cost (new model calls, removed caching, loop changes).
- Bulk file deletion or rename across many files.

## Allowed without approval

- Read files, search the codebase.
- Run unit and integration tests locally.
- Run linters and formatters.
- Generate local eval output (`eval/runs/`), provided no raw user text is stored.
- Local read-only exploration in a sandbox.

## Permission posture

Default: manual approval plus sandbox. The project holds API keys, AWS, RDS, S3,
OAuth, SMTP, user data, and a live AI cost surface, so the cost of an unreviewed
destructive action is high. Skip-permissions mode is not used in normal work; if
ever used, it is time-boxed, on a throwaway branch, never touching secrets,
production, or migrations, and the diff is reviewed before merge.

## Logging discipline

`ai_request_logs` stores `feature_name`, `model_name`, `prompt_version`,
`latency_ms`, `estimated_cost_usd`, `status`, and `request_correlation_id` — metadata,
not raw user content. Keep it that way. Diagnostics use the correlation id to trace a
pipeline run, not stored transcripts.

## Related

- `docs/architecture.md` — networking, secrets, observability (authoritative).
- `docs/quality-gates.md` — backend gate requires "no secret exposure".
- `CLAUDE.md` / `AGENTS.md` — approval rules referenced from the working agreement.

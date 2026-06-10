# Exec plan: sync BFF auth, voice, and review docs

## Goal
Sync the local PhraseLog docs with the accepted BFF auth handoff, S02/S04 voice transcription flow, S12 one-shot roleplay turn flow, and review-card scheduling decision.

## Source specs
- Uploaded references in `C:\Users\ywj95\Downloads\`: `010-bff-auth-handoff.md`, `auth.md`, `architecture.md`, `openapi.yaml`, `s02.md`, `s09.md`, `s10.md`, `AI_PIPELINE.md`, `data-model.md`, `DECISION_BACKLOG.md`, `INDEX.md`, `PRD.md`
- Local source of truth and workflow docs: `CLAUDE.md`, `SECURITY.md`, `docs/exec-plan-template.md`, `docs/quality-gates.md`

## Files expected to change
- Create `docs/decisions/010-bff-auth-handoff.md`
- Create `docs/DECISION_BACKLOG.md`
- Update BFF/auth contract docs: `docs/auth.md`, `docs/architecture.md`, `docs/api/openapi.yaml`
- Update screen and pipeline docs: `docs/screens/s02.md`, `docs/screens/s04.md`, `docs/screens/s06.md`, `docs/screens/s07.md`, `docs/screens/s09.md`, `docs/screens/s10.md`, `docs/screens/s12.md`, `docs/AI_PIPELINE.md`
- Update review/launch references: `docs/data-model.md`, `docs/PRD.md`, `docs/decisions/INDEX.md`

## Acceptance criteria
- `docs/api/openapi.yaml` is the internal Next.js BFF to Spring Boot contract and uses `internalAuth` / `X-Internal-Auth`.
- Anonymous S02 session attribution is documented as a `session_token` claim inside `X-Internal-Auth`, not as a direct Spring Boot session-token header.
- S02 and S04 voice input use `POST /transcriptions`, then user confirmation/editing, then text-only `POST /analysis`.
- S12 roleplay turns remain one-shot multipart on `/practice/sessions/{id}/turns`.
- New and re-added review cards use `next_review_at = now()` and `current_interval_days = 1`; interval 0 is rejected.
- Stale-string search has only justified remaining hits.

## Test plan
- Run the stale-string search requested in the task prompt.
- Parse `docs/api/openapi.yaml` as YAML.
- Run `git diff --check`.
- Run `git diff --stat` and `git diff --name-only`.

## Risk areas
- The uploaded references still contain stale browser-facing auth wording, so they must not be copied blindly.
- The OpenAPI no-auth override can remain only for explicitly public endpoints.
- If no local YAML parser is available, verification must use an approved parser download or report the blocker honestly.

## Decision log
- Use branch `docs-sync-bff-voice-review` because `docs/sync-bff-voice-review` could not be created in this checkout.
- Keep existing untracked local Claude settings untouched.

## Final outcome
Implemented. Local docs now reflect ADR-010 BFF auth, two-step S02/S04
transcription, S12 one-shot roleplay turns, and review-card initial scheduling.
The public landing examples endpoint keeps its explicit no-auth override.

## What changed after execution
Added `docs/screens/s05a.md` to the edit set after inspection showed one
ambiguous sentence about typed and voice input convergence. Branch creation also
used `docs-sync-bff-voice-review` because the slash-form branch name could not
be created in this checkout.

# Review - TTS Playback Endpoint (#30)

- **Date:** 2026-06-28
- **Reviewer:** Codex
- **Author:** Claude Code implementation under review
- **Target:** PR #102 / `feat/tts-playback-endpoint` / commit `d9e124a`
- **Related issue:** GitHub issue #30, `E04.5 TTS - POST /tts/playback + tts_audio_cache + S3`
- **Verdict:** **CHANGES REQUIRED before closing #30.** The core endpoint, OpenAI TTS
  client, content-hash cache, S3 storage boundary, S12 turn-audio delegation, and
  focused tests are mostly in place. The remaining gaps are around cache-row linking
  safety, cache-hit relinking, and logging after a successful provider call but failed
  downstream storage/persistence.

## Gate 1 - Spec Compliance

| Criterion | Result | Evidence |
|---|---|---|
| `POST /api/v1/tts/playback` exists | PASS | `TtsPlaybackController` maps `ApiPaths.V1 + "/tts"` and `POST /playback`. |
| Request accepts `text`, `voice_id`, optional `expression_variant_id` | PASS/PARTIAL | DTO and controller parse the fields, but the Next BFF still forwards only `{ text, voice_id }`. |
| Cache key is `(sha256(text), voice_id, model_name)` | PASS | `TtsPlaybackService` uses `sha256Hex(text)`, `voiceId`, and static model `tts-1`. |
| Cache hit skips OpenAI and logs `cache_hit` | PASS | Service returns from `servedFromCache`; unit test verifies no client interaction. |
| Cache miss calls OpenAI, writes S3, inserts cache row | PASS with logging caveat | Happy path is implemented and tested; post-provider downstream failures are not logged. |
| `expression_variants.tts_audio_cache_id` link | PARTIAL | Miss path links, but hit path does not; link update lacks owner/text validation. |
| Error contract on TTS provider failure | PASS | Provider 5xx/429/timeout/network map to `ApiErrorException`; controller/BFF callers can fallback. |
| 7-day signed URL from issue | NEEDS OWNER DECISION | PR changes the contract to 12h because instance-role SigV4 URLs are bounded by temporary credential lifetime. The reasoning is sound, but issue #30 still says 7-day expiry. |

## Gate 2 - Code Quality

| Criterion | Result | Evidence |
|---|---|---|
| Tests are meaningful | PASS with Docker caveat | Service/client/controller/unit tests pass; Testcontainers suites, including `JdbcTtsAudioCacheRepositoryIntegrationTest`, were skipped locally without Docker. |
| Auth and row ownership | NEEDS FIX | `userId` reaches the service but is not used when linking an expression variant. |
| Cost/logging safety | NEEDS FIX | Successful OpenAI calls can escape logging if S3 or cache persistence fails afterward. |
| No secret exposure | PASS | Reviewed diff does not add secrets or raw credential output. |
| Formatting/whitespace | PASS | `spotlessJavaCheck` and `git diff --check origin/main...HEAD` passed. |

## Findings

### P1 - Successful OpenAI calls can go unlogged if S3 or DB persistence fails

- **File:** `backend/src/main/java/com/phraselog/tts/service/TtsPlaybackService.java:103`
- **Evidence:** The provider call succeeds at line 103, but `audioStorage.putAudio(...)`
  runs at line 113 before the success log at line 122. If S3 is unavailable, upload
  fails, presign fails after insert, or a non-duplicate repository error occurs, the
  user gets an error after a charged OpenAI call with no `ai_request_logs` row.
- **Why it matters:** `docs/AI_PIPELINE.md:215` requires every OpenAI TTS call to write
  exactly one log row regardless of success or failure. This is the cost and error-rate
  audit trail for #30.
- **Suggested fix:** Once `client.synthesize(...)` returns bytes, record exactly one
  `tts_synthesis` log for that provider call even if later S3/cache/link work fails.
  Add a regression test where `AudioStorage.putAudio` throws after the fake OpenAI
  client returns bytes.

### P1 - `expression_variant_id` linking is not owner- or text-safe

- **File:** `backend/src/main/java/com/phraselog/tts/repository/JdbcTtsAudioCacheRepository.java:36`
- **Evidence:** `LINK_VARIANT_SQL` is `UPDATE expression_variants SET tts_audio_cache_id = ? WHERE id = ?`.
  `TtsPlaybackController` parses the variant id and passes `userId`, but the repository
  does not use user/session ownership and does not verify `expression_variants.english_text`
  matches the requested text.
- **Why it matters:** `docs/data-model.md:241` says the link is for this exact variant
  text. With the current SQL, a caller that can send a valid UUID can attach a cache row
  generated for arbitrary text/voice to any variant id it can guess or obtain.
- **Suggested fix:** Add a repository method that links through `expression_variants`
  joined to `expressions`, constrained by owner and exact `english_text`; reject or no-op
  safely on zero rows. Cover wrong-owner and wrong-text tests.

### P2 - Cache hits with `expression_variant_id` never persist the variant link

- **File:** `backend/src/main/java/com/phraselog/tts/service/TtsPlaybackService.java:78`
- **Evidence:** On cache hit, `playback(...)` returns `servedFromCache(...)` before
  considering `expressionVariantId`. `docs/api/openapi.yaml:847` says that when
  `expression_variant_id` is set, the backend persists the `tts_audio_cache_id` link.
- **Why it matters:** If the shared cache row already exists from another screen/user,
  the first playback for this variant returns audio but leaves
  `expression_variants.tts_audio_cache_id` null, so later screens cannot use the
  convenience cached URL path.
- **Suggested fix:** On hit, link the variant to the existing row using the same
  owner/text-safe method as the miss path.

### P2 - S12 opening coach turn is still not wired to #30 audio

- **File:** `backend/src/main/java/com/phraselog/practice/service/PracticeSessionService.java:256`
- **Evidence:** `toTurnResponse(...)` still hardcodes `tts_audio_url` to `null`, and the
  class comment still says opening-turn TTS is deferred to #30.
- **Why it matters:** PR #102 says the shared service backs the S12 coach-utterance path,
  and earlier #59/#60 reviews treat #30 as the dependency for roleplay audio URLs. Later
  turns now have `TtsPracticeAudioService`, but the session-start opening line remains
  text-only.
- **Suggested fix:** Either wire opening-turn synthesis in #30 or explicitly document
  that opening-turn audio is deferred to a follow-up distinct from this PR.

## Verification Run

- `gh issue view 30 --json ...`: issue #30 is still OPEN; requirements confirmed.
- `gh pr list --state all --search "30" --json ...`: PR #102 is OPEN on
  `feat/tts-playback-endpoint` and claims `Closes #30`.
- `gh pr checks 102`: Backend pass, Frontend pass.
- `.\gradlew.bat test --tests "com.phraselog.tts.*" --tests "com.phraselog.ai.client.service.RestClientOpenAiTtsClientTests" --tests "com.phraselog.practice.service.TtsPracticeAudioServiceTests"`: passed.
- `.\gradlew.bat cleanTest test --rerun-tasks`: `BUILD SUCCESSFUL`.
- `.\gradlew.bat spotlessJavaCheck`: `BUILD SUCCESSFUL`.
- `git diff --check origin/main...HEAD`: passed.
- Local Docker caveat: repository/Testcontainers suites were skipped locally, including
  `JdbcTtsAudioCacheRepositoryIntegrationTest` and `FlywayMigrationTests`.

## Local Workspace Caveat

The checkout is dirty outside the committed PR diff:

- `next-env.d.ts` has a generated route-types path change.
- `backend/bin/` contains untracked compiled class/resource output.

Do not include these in the PR unless intentionally cleaned/staged.

## Notes for the Owner

The 12-hour signed URL change is probably the right AWS choice, but it is a contract
change from the live issue's 7-day wording. Before closing #30, either accept that
change in the issue or ask the author to implement a different credential strategy for
longer-lived URLs.

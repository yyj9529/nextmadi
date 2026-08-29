# 수정된 개발 지도: 티켓 안에서 Fable5 / Codex / Commands / Hooks / 참조문서까지 같이 보기

## 기본 원칙

Commands와 hooks는 별도 “작업 단계”가 아닙니다.  
**각 티켓을 처리할 때 앞뒤에 붙는 안전장치/검증도구**입니다.

- **Fable5**: 품질 판단, 프롬프트, eval case, persona, 상태머신 리뷰
- **Codex**: repo 확인, 구현, 테스트, CI, GitHub issue/PR 정리
- **Commands**: 그 티켓에서 현재 상태 확인/검증/실행할 명령
- **Hooks**: 그 티켓 작업 중 실수 방지 또는 세션 종료 체크
- **참조문서**: Fable5에 줄 문서와 Codex 구현에 줄 문서를 구분
- **Output**: 결과가 어디에 남아야 하는지 명시

### Commands 운영 원칙

티켓마다 모든 명령을 길게 나열하지 않습니다.  
각 티켓의 Commands는 아래 4가지 중 필요한 것만 씁니다.

- **Required context**: issue/repo 상태 확인처럼 작업 시작 전에 꼭 필요한 저비용 명령
- **Cheap validation**: JSON parse, file existence, frontmatter 확인처럼 API 비용 없는 검증
- **Post-change verification**: 구현 후 실제로 돌릴 테스트/build/lint 명령
- **Gated/live**: Anthropic/OpenAI/API/eval처럼 비용, secret, 외부 호출이 있어 승인 후 실행할 명령

공통 규칙:

- 구현 전에는 `git status --short --branch`로 현재 상태를 확인합니다.
- issue 기반 작업은 `gh issue view <id> --comments --repo yyj9529/nextmadi`로 scope를 확인합니다.
- PR/handoff 전에는 관련 테스트와 `git diff --check`를 실행합니다.
- 아직 backend scaffold가 없으면 “backend tests”를 실제 명령처럼 쓰지 말고 “verification target”으로 둡니다.
- live eval은 dependency, `ANTHROPIC_API_KEY`, 비용 승인 확인 전에는 실행하지 않습니다.

### Hooks 운영 원칙

Hooks는 모든 티켓에 반복해서 쓰지 않습니다.  
기본 safety rail로 보고, 티켓별로 특별한 risk가 있을 때만 적습니다.

- `PermissionRequest`: dependency install, deploy, DB migration, secrets 접근, live provider/API call, AI cost 변화
- `PreToolUse`: destructive command, destructive SQL, bulk delete, secret 출력, prompt/frontmatter 손상 위험
- `PostToolUse`: eval/log output에 raw user text가 들어갔는지 확인해야 할 때
- `Stop`: 코드 변경, 결정, 오류 해결, 의미 있는 검증이 있었으면 dev-log 작성 reminder

## 1. #66 S07 eval case review

- **작업 주체**
  - 먼저 **Codex commands**로 상태 확인
  - 그다음 **Fable5**로 eval case 품질 리뷰
  - 마지막에 **Codex**로 검증/issue comment 정리

- **이유**
  - #66은 코드 구현보다 “이 케이스들이 PhraseLog 제품 의도에 맞는가”가 핵심이라 Fable5가 적합합니다.
  - Codex는 repo 상태와 JSON/eval 실행 가능성을 확인하는 보조 역할입니다.

- **Output**
  - 기본: issue #66 comment
  - 리뷰가 길거나 나중에 다시 봐야 하면: `docs/reviews/YYYY-MM-DD-s07-eval-cases-review.md`

- **Fable5에 참조시킬 문서**
  - `eval/s07-analysis/test_cases.json`
  - `docs/EVAL_PLAN.md`
  - `docs/AI_PIPELINE.md` 중 `s07_analysis_v1`
  - `PROJECT_CONTEXT.md`
  - 목적: 케이스 wording, tone intent, expected failure modes가 제품 철학과 맞는지 검토

- **Codex에 참조시킬 문서**
  - `eval/s07-analysis/test_cases.json`
  - `eval/s07-analysis/run_eval.py`
  - `docs/quality-gates.md`
  - 목적: JSON parse, category count, eval runner 실행 가능성 확인

- **Commands**
  - Required context: `git status --short --branch`
  - Required context: `gh issue view 66 --comments --repo yyj9529/nextmadi`
  - Cheap validation: JSON parse / category count
  - Gated/live: `python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1`

- **Hooks**
  - 전역 hook 규칙 적용
  - 특별히 중요한 hook: live eval은 `PermissionRequest` 필요

- **Codex skill**
  - `ce-plan` 사용하지 않음
  - `ce-optimize`는 실제 eval 결과를 보고 prompt/case 개선 루프를 돌릴 때만 사용

## 2. #58 Roleplay Prompt v1 review

- **작업 주체**
  - 먼저 **Codex commands**로 prompt 파일 존재/frontmatter 확인
  - 그다음 **Fable5**로 prompt/persona 품질 리뷰
  - 마지막에 **Codex**로 #58 comment 정리

- **이유**
  - persona 일관성, feedback tone, coach 발화 비율은 Fable5가 잘 봅니다.
  - Codex는 markdown/frontmatter/schema 연결 상태를 확인합니다.

- **Output**
  - 기본: issue #58 comment
  - 수정 제안이 많으면: checkboxes 또는 `docs/reviews/YYYY-MM-DD-roleplay-prompts-review.md`

- **Fable5에 참조시킬 문서**
  - `prompts/roleplay/init/v1.md`
  - `prompts/roleplay/turn/v1.md`
  - `prompts/roleplay/feedback/v1.md`
  - `prompts/roleplay/result/v1.md`
  - `prompts/roleplay/mia/v1.md`
  - `prompts/roleplay/david/v1.md`
  - `prompts/roleplay/sarah/v1.md`
  - `docs/screens/s12.md`
  - `docs/screens/s12b.md`
  - `PROJECT_CONTEXT.md`
  - 목적: coach tone, shame-reducing feedback, roleplay flow 품질 검토

- **Codex에 참조시킬 문서**
  - 위 prompt 7개
  - `docs/AI_PIPELINE.md`
  - `docs/data-model.md`의 `coach_profiles.prompt_template_ref`
  - #58 issue body
  - 목적: frontmatter 필드와 runtime loader 요구사항 확인

- **Commands**
  - Required context: `gh issue view 58 --comments --repo yyj9529/nextmadi`
  - Cheap validation: prompt 7개 파일 존재 확인
  - Cheap validation: frontmatter 필드 확인: `feature`, `prompt_version`, `model`, `output_schema`, `created`
  - Gated/live: 없음. sample call/eval 전까지 비용 없음

- **Hooks**
  - 전역 hook 규칙 적용
  - prompt 파일 수정이 포함되면 `PreToolUse`로 schema/frontmatter 손상 방지

- **Codex skill**
  - `ce-plan` 사용하지 않음
  - `ce-code-review`는 prompt diff를 기계적으로 검토할 때만 사용
  - `ce-optimize`는 sample call/eval이 가능해진 뒤 사용

## 3. #9 Spring Boot scaffold, #10 CI

- **작업 주체**
  - **Codex**

- **이유**
  - backend source tree와 CI가 없으면 #14/#28/#27/#26을 구현 완료할 수 없습니다.
  - Fable5를 쓸 품질 판단 영역이 거의 없습니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-backend-scaffold-ci.md`
  - backend scaffold
  - CI workflow update
  - PR summary

- **Fable5에 참조시킬 문서**
  - 없음

- **Codex에 참조시킬 문서**
  - `docs/architecture.md`
  - `docs/api/openapi.yaml`
  - `SECURITY.md`
  - `docs/quality-gates.md`

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: backend test command, scaffold 생성 후 확정
  - Post-change verification: `bun run lint`, `bun run typecheck`, `bun run build` if frontend can be affected
  - 화면이 바뀌었으면 위 세 가지로 끝이 아니다. `bun run dev`를 띄우고 브라우저로 확인해야 UI 게이트를 통과한다 (`docs/quality-gates.md` "UI change")
  - PR 전: `git diff --check`

- **Hooks**
  - 전역 hook 규칙 적용
  - dependency install은 `PermissionRequest` 필요
  - deploy/secrets 접근은 이 티켓 scope 밖. 필요하면 중단하고 승인 요청

- **Codex skill**
  - 시작: `ce-plan`
  - 구현: `ce-work`
  - 완료 전: `ce-code-review`

## 4. #14 Flyway schema, #77 Error Response Contract

- **작업 주체**
  - **Codex**
  - Fable5는 error taxonomy가 애매할 때만 선택

- **이유**
  - DB schema와 error contract가 먼저 안정돼야 #27 logging, #39 analysis, #60 roleplay가 덜 흔들립니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-flyway-error-contract.md`
  - migration / schema implementation
  - error response contract implementation
  - risky backend change이면 `docs/reviews/` verdict

- **Fable5에 참조시킬 문서**
  - 선택: `docs/harness.md` error response contract
  - 선택: `docs/AI_PIPELINE.md` fallback policy
  - 목적: error code taxonomy가 UX/AI failure에 충분한지 리뷰

- **Codex에 참조시킬 문서**
  - `docs/data-model.md`
  - `docs/api/openapi.yaml`
  - `docs/harness.md`
  - `SECURITY.md`

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: migration tests / service tests
  - Cheap validation: OpenAPI validation command, tooling이 있으면 실행
  - PR 전: `git diff --check`

- **Hooks**
  - DB migration은 `PermissionRequest` 필요
  - destructive SQL/삭제 위험은 `PreToolUse` 필요
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - `ce-plan`
  - `ce-work`
  - 실패 시 `ce-debug`
  - 완료 전 `ce-code-review`

## 5. #28 Prompt Loader

- **작업 주체**
  - **Codex**

- **이유**
  - #58 prompt를 runtime에서 읽게 하는 구현 티켓입니다. Fable5보다 Codex가 적합합니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-prompt-loader.md`
  - prompt loader implementation
  - loader tests

- **Fable5에 참조시킬 문서**
  - 없음

- **Codex에 참조시킬 문서**
  - `prompts/s07/v1.md`
  - `prompts/roleplay/**/v1.md`
  - `docs/AI_PIPELINE.md`
  - #58 issue body

- **Commands**
  - Required context: `git status --short --branch`
  - Cheap validation: prompt fixture parse test
  - Post-change verification: loader unit test
  - PR 전: `git diff --check`

- **Hooks**
  - prompt file edit 시 frontmatter 손상 방지 `PreToolUse`가 있으면 사용
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - `ce-plan` 짧게
  - `ce-work`
  - 완료 전 `ce-code-review`

## 6. #27 ai_request_logs Logging Module

- **작업 주체**
  - **Codex**

- **이유**
  - 모든 AI/STT/TTS 호출의 공통 기록 기반입니다. 구현/테스트 중심입니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-ai-request-logs.md`
  - logging module implementation
  - raw user text 저장 금지 테스트

- **Fable5에 참조시킬 문서**
  - 없음

- **Codex에 참조시킬 문서**
  - `docs/data-model.md`의 `ai_request_logs`
  - `docs/AI_PIPELINE.md` Logging contract
  - `SECURITY.md`

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: logging unit/integration test
  - Post-change verification: raw user text 저장 금지 테스트
  - PR 전: `git diff --check`

- **Hooks**
  - user data/logging 관련 변경이므로 raw text 저장 여부를 특히 확인
  - secrets/raw text 출력 위험이 있으면 `PreToolUse`
  - 로그 output 검사가 필요하면 `PostToolUse`

- **Codex skill**
  - `ce-plan`
  - `ce-work`
  - 실패 시 `ce-debug`
  - 완료 전 `ce-code-review`

## 7. #26 Anthropic Client

- **작업 주체**
  - 기본은 **Codex 구현**
  - Fable5는 기존 handoff가 stale이거나 schema/fallback/retry matrix가 바뀔 때만 다시 사용

- **이유**
  - schema/fallback/retry matrix는 이미 `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`에 정리되어 있습니다.
  - 실제 Anthropic client, retry, schema validation, logging 연결은 Codex가 구현합니다.

- **Output**
  - 기존 handoff를 쓰거나, 필요하면 새 `docs/exec-plans/YYYY-MM-DD-anthropic-client.md`
  - Anthropic client implementation
  - AI pipeline risky change이므로 `docs/reviews/` verdict

- **Fable5에 참조시킬 문서**
  - 기본적으로 다시 부르지 않음
  - 필요할 때만:
    - `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`
    - `docs/AI_PIPELINE.md`
    - `docs/quality-gates.md`
  - 목적: schema rule, fallback matrix, retry policy 변경분 검토

- **Codex에 참조시킬 문서**
  - `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`
  - `docs/AI_PIPELINE.md`
  - `docs/quality-gates.md`
  - `prompts/**`
  - #27 logging 구현 결과
  - #28 prompt loader 구현 결과
  - #77 error contract 구현 결과

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: schema validation unit test
  - Post-change verification: retry/fallback unit test
  - Post-change verification: fake clock/provider mock test
  - Post-change verification: logging integration test
  - PR 전: `git diff --check`
  - Gated/live: Anthropic API live call은 승인 전 실행하지 않음

- **Hooks**
  - Anthropic API live call 또는 cost 발생은 `PermissionRequest` 필요
  - secrets 출력 위험은 `PreToolUse`
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - Fable5 리뷰 단계: 사용하지 않음
  - Codex 구현 시작: `ce-plan`
  - 구현: `ce-work`
  - 실패: `ce-debug`
  - 완료 전: `ce-code-review`

## 8. #65 eval.yml, #39 S07 backend

- **작업 주체**
  - **Codex**
  - Fable5는 eval failure 해석 때만 사용

- **이유**
  - CI wiring과 backend endpoint는 구현 중심입니다.
  - eval 결과가 나쁘면 그때 Fable5가 prompt/eval 품질 원인을 봅니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-s07-backend-eval-ci.md`
  - S07 backend implementation
  - eval CI wiring
  - eval evidence or CI evidence in PR/issue

- **Fable5에 참조시킬 문서**
  - eval 실패 시:
    - `eval/runs/<latest>.json`
    - `eval/s07-analysis/test_cases.json`
    - `prompts/s07/v1.md`
    - `docs/EVAL_PLAN.md`

- **Codex에 참조시킬 문서**
  - `.github/workflows/eval.yml`
  - `eval/s07-analysis/run_eval.py`
  - `docs/screens/s02.md`
  - `docs/screens/s05a.md`
  - `docs/screens/s06.md`
  - `docs/screens/s07.md`
  - `docs/api/openapi.yaml`
  - `docs/AI_PIPELINE.md`

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: backend tests, backend scaffold 이후 확정
  - Post-change verification: CI check command, workflow 확정 후 실행
  - PR 전: `git diff --check`
  - Gated/live: `python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1`

- **Hooks**
  - live eval/API cost는 `PermissionRequest` 필요
  - eval output에 raw user text가 들어갔는지 `PostToolUse`로 확인하면 좋음
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - `ce-plan`
  - `ce-work`
  - CI 실패 시 `ce-debug`
  - eval 품질 개선 시 `ce-optimize`

## 9. #29 STT, #30 TTS, #59 Roleplay Session Start

- **작업 주체**
  - **Codex**

- **이유**
  - provider client, cache, session persistence 구현입니다.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-roleplay-provider-session-start.md`
  - STT/TTS provider client implementation
  - audio cache/session start implementation
  - provider/session tests

- **Fable5에 참조시킬 문서**
  - 보통 없음
  - #59 opening scenario 품질이 애매하면:
    - `prompts/roleplay/init/v1.md`
    - `docs/screens/s12.md`

- **Codex에 참조시킬 문서**
  - `docs/AI_PIPELINE.md`
  - `docs/api/openapi.yaml`
  - `docs/data-model.md`
  - `docs/screens/s12.md`

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: provider mock tests
  - Post-change verification: cache tests
  - Post-change verification: session start/retrieval tests
  - Gated/live: OpenAI STT/TTS live call, S3, secrets 접근은 승인 전 실행하지 않음

- **Hooks**
  - OpenAI STT/TTS live call, S3, secrets 접근은 `PermissionRequest` 필요
  - secrets 출력 위험은 `PreToolUse`
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - `ce-plan`
  - `ce-work`
  - provider 실패 시 `ce-debug`
  - 완료 전 `ce-code-review`

## 10. #60 Turn Pipeline

- **작업 주체**
  - 기본은 **Codex 구현**
  - Fable5는 기존 handoff가 stale이거나 실패모드/대화 품질 matrix가 바뀔 때만 다시 사용

- **이유**
  - STT → Sonnet → Haiku → TTS 상태머신은 복잡하고 edge case가 많습니다.
  - 하지만 기본 실패모드와 테스트 matrix는 이미 `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`에 있습니다.
  - 따라서 먼저 기존 handoff를 재사용하고, 바뀐 부분이 있을 때만 Fable5를 다시 부릅니다.

- **Output**
  - 기존 handoff를 쓰거나, 필요하면 새 `docs/exec-plans/YYYY-MM-DD-roleplay-turn-pipeline.md`
  - turn pipeline implementation
  - risky roleplay state-machine change이므로 `docs/reviews/` verdict

- **Fable5에 참조시킬 문서**
  - 기본적으로 다시 부르지 않음
  - 필요할 때만:
    - `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`
    - `docs/screens/s12.md`
    - `docs/AI_PIPELINE.md`
    - `prompts/roleplay/turn/v1.md`
    - `prompts/roleplay/feedback/v1.md`
  - 목적: low-confidence STT, feedback failure, coach ratio, turn flow 변경분 검토

- **Codex에 참조시킬 문서**
  - `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`
  - `docs/screens/s12.md`
  - `docs/AI_PIPELINE.md`
  - `prompts/roleplay/turn/v1.md`
  - `prompts/roleplay/feedback/v1.md`
  - `docs/data-model.md`
  - `docs/api/openapi.yaml`
  - #26, #27, #29, #30, #59 구현 결과

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: turn state-machine tests
  - Post-change verification: idempotency tests
  - Post-change verification: concurrent duplicate submission tests
  - Post-change verification: provider failure tests
  - Post-change verification: shared `request_correlation_id` tests
  - Gated/live: STT/LLM/TTS live calls은 승인 전 실행하지 않음

- **Hooks**
  - live STT/LLM/TTS cost는 `PermissionRequest` 필요
  - destructive DB/test data 삭제 위험은 `PreToolUse`
  - 실패 로그에 raw user text가 남지 않았는지 `PostToolUse`로 확인하면 좋음
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - Fable5 리뷰 단계: 사용하지 않음
  - Codex 구현 시작: `ce-plan` 깊게
  - 구현: `ce-work`
  - 실패: `ce-debug`
  - 완료 전: `ce-code-review`

## 11. #62 Roleplay Result Generation/Save

- **작업 주체**
  - **Codex 구현**
  - **Fable5 품질 리뷰**

- **이유**
  - result 저장/idempotency는 Codex 구현.
  - result summary, recommended expressions, encouragement tone은 Fable5 품질 리뷰.

- **Output**
  - `docs/exec-plans/YYYY-MM-DD-roleplay-result-save.md`
  - result generation/save implementation
  - Fable5 품질 리뷰 note는 issue comment 또는 `docs/reviews/`

- **Fable5에 참조시킬 문서**
  - `prompts/roleplay/result/v1.md`
  - `docs/screens/s12b.md`
  - `docs/AI_PIPELINE.md`
  - `PROJECT_CONTEXT.md`
  - 목적: result tone, 추천 표현 품질, shame-reducing encouragement 확인

- **Codex에 참조시킬 문서**
  - `docs/screens/s12b.md`
  - `docs/api/openapi.yaml`
  - `docs/data-model.md`
  - `docs/AI_PIPELINE.md`
  - #60 구현 결과

- **Commands**
  - Required context: `git status --short --branch`
  - Post-change verification: result generation tests
  - Post-change verification: cached result/idempotency tests
  - Post-change verification: save-expression tests
  - Gated/live: live LLM call은 승인 전 실행하지 않음

- **Hooks**
  - live LLM call/cost는 `PermissionRequest` 필요
  - result_json/raw text 저장 정책 위반 위험은 `PreToolUse` 또는 review check
  - 전역 `Stop` dev-log rule 적용

- **Codex skill**
  - 구현 시작: `ce-plan`
  - 구현: `ce-work`
  - 실패: `ce-debug`
  - 완료 전: `ce-code-review`

## 한 줄 운영 규칙

- **Fable5가 먼저 보는 티켓**: #66, #58, #62 품질
- **Fable5를 조건부로 다시 보는 티켓**: #26 설계 변경, #60 설계 변경, #39/#65 eval failure 해석
- **Codex가 바로 구현하는 티켓**: #9, #10, #14, #77, #28, #27, #65, #39, #29, #30, #59
- **기존 handoff를 먼저 재사용하는 티켓**: #26, #60
- **Commands는 모든 티켓에 길게 붙이지 않는다**: required context, cheap validation, post-change verification, gated/live만 적는다
- **Hooks는 모든 티켓에 반복하지 않는다**: 특별 risk가 있을 때만 티켓 안에 적고, 나머지는 전역 규칙을 따른다
- **참조문서는 Fable5에는 “품질 판단 문서”, Codex에는 “구현 계약 문서”를 준다**
- **Output은 반드시 남긴다**: 구현은 `docs/exec-plans/`, 리뷰는 `docs/reviews/`, 짧은 검토는 issue/PR comment

# Ticket #26 — Anthropic Client fallback/retry 구현

Date: 2026-07-13
Status: Done (리뷰 PASS-with-questions — `docs/reviews/2026-07-13-ticket-26-fallback-retry-review.md`)
Issue: #26 (Anthropic Client — Routing/Schema/Fallback)
구현: Opus 4.8 (owner 승인, 2026-07-13 — 카드 기본 배정 Codex 대신 Opus 구현+리뷰)
리뷰: spec-reviewer 서브에이전트 (스펙 대조) — 구현 diff를 handoff matrix와 대조

## 배경 (감사 결과)

이슈 #26은 2026-07-06 감사에서 PARTIAL로 재분류됨.

- DONE: 라우팅(`FeatureRouting`) + JSON 스키마 검증(`JsonSchemaValidator`)
- REMAINING: fallback/retry 미구현. 현재 `AnthropicService.handleClientException`는
  retryable 에러에 대해 `Thread.sleep(backoff)` 후 **재호출 없이 그대로 throw**한다
  (단일 시도, 재invoke 없음, 스키마 재시도 없음).

## 설계 근거 (source of truth)

`docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md`의 fallback matrix. 신규 설계
아님 — 이미 정의된 matrix를 코드로 구현하는 작업.

| 실패 | 재시도 | 최종 error_code |
| --- | --- | --- |
| Invalid JSON / schema mismatch | constraint reminder 붙여 1회 | `schema_validation_failed` |
| Provider 5xx | 500ms 후 1회 | `provider_5xx` |
| Provider 429 | 1s, 3s 2회 | `provider_429` |
| Timeout | 재시도 없음 | `timeout` |
| Network error | 500ms 후 1회 | `network` |

model fallback은 matrix에 없음 → 구현하지 않는다(같은 모델 재시도만). model
fallback 추가는 설계 변경이므로 별도 결정 사항.

## 구현 계획

`AnthropicService.callClaude`를 단일-시도 + `handleClientException` 구조에서
**재호출 루프**로 변경한다.

1. **재시도 루프**: 첫 실패의 error_code로 재시도 스케줄을 결정(결정론적), 각 재시도는
   클라이언트를 실제로 다시 호출하고 스키마를 재검증한다.
   - schedule: SCHEMA→[0ms], 5XX/NETWORK→[500ms], 429→[1000ms,3000ms], TIMEOUT→[]
   - 최종 error_code = 마지막 시도의 실패 코드(동일-타입 재시도이면 첫 실패와 동일).
2. **schema 재시도**: 스키마 실패 시 재호출 messages에 constraint reminder를 **정확히
   1회** 덧붙인다(`buildMessages` 뒤에 reminder user 메시지 추가).
3. **로깅 불변식**: `callClaude` 1회당 #27 로깅은 **최종 상태에서 정확히 1회**
   (성공 또는 최종 실패). 중간 재시도는 로깅하지 않는다. correlation_id 동일.
4. **테스트 가능한 sleep**: `Sleeper` 함수형 seam 도입(기본 `Thread::sleep`,
   테스트에서 fake로 주입) → 1s/3s 실제 대기 없이 429 스케줄 검증.

## 테스트 매트릭스 (handoff 명시)

- schema 검증이 5개 스키마 각각의 필수 필드 누락을 거부 (기존 커버 확인)
- schema 재시도가 constraint reminder를 정확히 1회 덧붙임
- provider 5xx: 1회 재시도 후 `provider_5xx` 반환
- provider 429: fake clock으로 1s/3s 2회 재시도
- timeout: 재시도 안 함
- network: 1회 재시도 후 `network`
- 최종 상태마다 #27 로깅 정확히 1회, 동일 correlation_id
- 재시도 성공 시: 성공 로깅 1회, 성공 응답 반환

## 리스크

- sleep seam 도입으로 Spring 생성자 시그니처 불변 유지(테스트 전용 package-private
  setter). 프로덕션 경로는 기본 `Thread::sleep`.
- 로깅 중복/누락이 가장 큰 위험 → 단일 terminal 로깅 지점으로 통일.

## 리뷰 반영 (2026-07-13, spec-reviewer PASS-with-questions)

- **혼합 에러 시퀀스(수용)**: 재시도 스케줄은 firstError로 고정, terminal error_code는
  lastError. 첫 5xx 후 재시도에서 스키마 실패 시 reminder 재시도 없이
  `schema_validation_failed`로 종료. deterministic·bounded한 의도된 동작 →
  `mixedFirst5xxThenSchemaFailureUsesFirstErrorScheduleAndLastErrorCode` 테스트로 고정.
- **5개 스키마 reject 테스트 보강(완료, 2026-07-13)**: `JsonSchemaValidatorTests`에
  `roleplay_session_init`(accept + planned_turns 3-10 range reject),
  `roleplay_turn_response`(coach_utterance non-empty reject),
  `roleplay_result`(coach_encouragement 필수 reject) 추가. 이제 5개 스키마 전부 accept +
  reject 커버. → #26 close criteria "tests for all 5 schemas" 충족.
- **미분류 RuntimeException 경로**: 인터페이스 계약상 미발생, robustness 노트로만 남김.

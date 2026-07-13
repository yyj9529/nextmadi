# Ticket #26 — Anthropic Client fallback/retry 리뷰

Date: 2026-07-13
Reviewer: spec-reviewer (독립 스펙 대조, 읽기 전용)
Implementer: Opus 4.8
Verdict: **PASS-with-questions** (머지 차단 사유 없음)

Source of truth: `docs/exec-plans/2026-06-12-fable-ai-ticket-handoff.md` (#26 fallback matrix + Implementation tests)

## 확인된 정합성

- **Fallback matrix 5행 정확 일치**: schema=1회, 5xx=500ms 1회, 429=1s/3s 2회, timeout=0회,
  network=500ms 1회. terminal error_code→wire name 매핑 일치.
- **로깅 불변식 성립**: 모든 종료 경로(성공/비재시도 에러/스케줄 소진/InterruptedException)가
  단일 terminal `logCall` 지점으로 수렴. 중간 재시도는 `log.warn`만(#27 저장 아님).
  correlation_id는 terminal까지 전파, 테스트가 성공·실패 경로 모두 assert.
- **Constraint reminder 정확히 1회**: messages를 매 반복 base에서 파생 → 누적 불가.
- **스펙 외 "개선" 없음**: 계획대로 same-model 재시도만. model fallback 등 미도입.
- edge case: InterruptedException 재-interrupt + terminal 로깅, UNKNOWN graceful 처리.

## Questions

1. **[Medium] 혼합 에러 시퀀스** — 스케줄은 firstError 고정, terminal code는 lastError.
   예: 첫 5xx→재시도에서 스키마 실패 시 terminal=`schema_validation_failed`이지만 reminder
   재시도는 수행되지 않음(5xx budget 소진). deterministic·bounded·계획서에 명시된 의도된 설계.
   → **수용**. 동작을 고정하는 테스트
   `mixedFirst5xxThenSchemaFailureUsesFirstErrorScheduleAndLastErrorCode` 추가(2026-07-13).
2. **[Low] "5개 스키마 필수필드 누락 거부" — 해결(2026-07-13)**. `JsonSchemaValidatorTests`에
   `roleplay_session_init`(accept + planned_turns 3-10 range reject), `roleplay_turn_response`
   (coach_utterance non-empty reject), `roleplay_result`(coach_encouragement 필수 reject) 추가.
   5개 스키마 전부 accept + reject 커버 → #26 close criteria 충족.
3. **[Low] 미분류 RuntimeException 경로** — 인터페이스 계약상 발생 안 하도록 되어 있으나,
   계약 외 런타임 예외는 #27 로깅·ApiError 매핑 없이 전파. robustness 노트.

## 결론

matrix 정합성·로깅 불변식·reminder 1회·edge case 처리는 스펙을 정확히 준수. Question 1은
문서화 테스트 추가로 반영. Question 2는 티켓 close 조건으로 별도 추적. 머지 진행 가능.

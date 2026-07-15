# 리뷰: #32 GET /landing/examples API

- **날짜**: 2026-07-16
- **구현**: Claude Code (Opus 4.8) — 카드 배정은 Sonnet 4.6, 모델 상이 owner 고지 후 진행
- **리뷰**: 독립 spec-reviewer (구현과 다른 컨텍스트, 읽기 전용)
- **브랜치**: `feat/ticket-32-landing-examples-api`
- **성격**: API/백엔드, 읽기 전용 공개 엔드포인트 (S01 랜딩 데이터)

## 대조한 계약

- `docs/api/openapi.yaml` — `/landing/examples` (58-77), `LandingExample` 스키마 (1023-1032)
- `docs/data-model.md` — `landing_examples` (102-115)
- `docs/screens/s01.md` — UI states (34-42)
- 이슈 #32 Acceptance Criteria
- `InternalAuthFilter.java` 공개 화이트리스트 (89-92)

## 결과 요약

| 요구사항 | 판정 |
|---|---|
| envelope key `examples` | PASS |
| item `id`(uuid), `korean_text`(snake_case) | PASS (`@JsonProperty`) |
| minItems 0 / maxItems 3 | PASS (`SAMPLE_SIZE=3`, 빈 풀 → 빈 리스트) |
| 쿼리 `is_active=true` + `ORDER BY random()` + `LIMIT 3` | PASS |
| `security: []` 공개 화이트리스트 정합 | PASS (exact-match) |
| 공개 엔드포인트 principal 미추출 (Coach와 의도적 차이) | PASS |
| AC(a) 0건 → 빈 배열 | PASS (repository 테스트) |
| AC(b) 매 방문 다른 표본 | PASS (20회 표본, 결정론적이지 않음) |
| Coach 모듈 패턴 일관성 (repo/service/config/Unavailable) | PASS |

## Blocker

없음.

## Question (비차단)

1. **표본 회전(CTR 기반) 미구현** — `data-model.md`와 코드 주석이 v1.1+ 유보로 명시. 스펙 준수.
2. **랜덤성 테스트 비결정성** — 12행 중 3개 20회 표본, 이론상 flaky 확률 사실상 0. CI 안정성 관점에서 인지만 필요.
3. **`UnavailableLandingExampleRepository`가 `IllegalStateException`** — no-DB 스캐폴드 폴백, Coach와 동일 패턴. 프로덕션엔 DataSource 상존. HTTP 500 매핑은 실경로 아님이라 별도 테스트 없음.

## PII / 불가역 작업

- 반환 데이터는 시드된 한국어 예시 문장뿐. 사용자 원문 저장 없음.
- V007 마이그레이션은 #15에서 이미 머지됨. 이 diff는 읽기 전용, 파괴적 작업 없음.

## 최종 판정: PASS-with-questions

스펙 위반 없음. question 3건은 모두 스펙 명시 유보이거나 비-프로덕션 경로로 차단 사유 아님.

## CI 게이트

`./gradlew spotlessApply spotlessCheck test build` → BUILD SUCCESSFUL (repository testcontainer 테스트는 Docker 부재 시 skip).

# Exec plan: ai_request_logs 재시도 시도 단위 기록

Date: 2026-08-02
Status: Proposed — owner 승인 대기 (STEP 1)
관련: `AI_PIPELINE.md` Logging contract / Timeout and fallback policy, `data-model.md`
`ai_request_logs`, `architecture.md` Alerts, PRD 3.2 / 6, ADR-001, 신규 ADR-011(예정)

## Goal

`ai_request_logs`가 재시도를 접어서 한 행으로 기록하기 때문에 (1) 실패한 시도의 토큰이
비용 집계에서 누락되고, (2) 재시도율을 SQL로 측정할 수 없어 PRD 3.2의 "JSON schema
failure rate"가 측정 불가능하며, (3) `architecture.md`의 error rate 알림이 재시도로
복구된 실패를 보지 못한다. 로깅 계약을 시도 단위로 바꿔 셋을 동시에 해소한다.

## Source specs

- `docs/AI_PIPELINE.md:221-246` — Logging contract ("writes exactly one row", `latency_ms`
  including retries)
- `docs/AI_PIPELINE.md:248-264` — Timeout and fallback policy (재시도 매트릭스)
- `docs/data-model.md:444-483` — `ai_request_logs` DDL, 컬럼 설명, 인덱스 4개
- `docs/architecture.md:305-325` — Alerts (v1 minimum)
- `docs/PRD.md:37-42` — Post-launch metrics / `docs/PRD.md:144-150` — Cross-cutting
- `docs/exec-plans/2026-07-13-ticket-26-fallback-retry.md` — 재시도 루프 원 설계.
  본 계획은 그 설계의 **로깅 불변식만** 바꾼다. 재시도 스케줄은 건드리지 않는다.

## STEP 0 검증 요약 (근거)

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| 비용 과소집계 | CONFIRMED | 성공 시 마지막 시도 토큰만 기록(`AnthropicService.java:104-123`). 최종 실패 시 `inputTokens=null, outputTokens=null, cost=null`(`:158-170`) |
| 재시도율 측정 불가 | CONFIRMED (부분 정정) | DDL에 attempt 컬럼 없음. 단 `AnthropicService:144` WARN 로그로 간접 측정은 가능 — "SQL 불가"가 정확한 표현 |
| 알림 오작동 | CONFIRMED | `architecture.md:313`이 터미널 행만 관측 |
| prompt_version 불일치 | PARTIALLY CORRECT | reminder는 prompt body 교체가 아니라 user 메시지 1개 추가(`:199-207`). 현재 행이 하나뿐이라 **현 시점 결함 아님 — A안 채택 시 발생** |
| timeout 토큰 미기록 | CONFIRMED | `:158-170` 경로로 null. timeout은 재시도 없음 |
| cache_hit 비용 미정의 | PARTIALLY CORRECT | 문서엔 없으나 **코드는 이미 `BigDecimal.ZERO`**(`TtsPlaybackService.java:250`) — 문서-코드 드리프트 |

추가 발견(원 진단 목록 외):

- **`error_code`가 마지막 시도 것만 남는다.** `firstError`는 계산되지만(`:86,135-137`)
  기록되지 않고 `lastError`가 기록된다(`:166`). 5xx→재시도→schema 실패면 5xx가 소실된다.
- **`latency_ms`가 백오프 sleep을 포함한다.** `startTimeMs`가 루프 밖(`:71`). 429 경로는
  최대 4초의 대기가 지연에 섞여 `AI_PIPELINE.md:329`의 라우팅 튜닝 근거를 오염시킨다.
- **`correlation_id` 계층이 문서-코드 불일치.** 문서(`:230`)는 "user action 시작 시 생성"
  이나 `AnalysisService.java:123`은 LLM 호출 직전 생성. S07에는 상위 레벨이 아직 없다.
- **A안이 기존 쿼리 하나를 깨뜨린다.** `JdbcAnalysisRepository.java:134-146`의
  `findLogIdByCorrelation`이 `ORDER BY created_at DESC LIMIT 1`로 한 행을 고른다.
- **STT/TTS에는 재시도 코드가 없다.** `transcription/`·`tts/` 전체 0건 — 행 증가 대상 아님.
- 마이그레이션은 V007까지 적용됨 → 신규는 **V008**.

## 설계안 비교

### A안 — 시도마다 한 행

```sql
attempt_number   SMALLINT NOT NULL DEFAULT 1
is_final_attempt BOOLEAN  NOT NULL DEFAULT true
attempt_group_id UUID     NOT NULL
```

- 기존 "요청 수" 쿼리는 `WHERE is_final_attempt` 로 의미 보존.
- 비용은 조건 없이 `SUM(estimated_cost_usd)` — 필터를 빼먹으면 과대가 아니라 정확해진다.
- `error_code`가 시도마다 자기 값을 가지므로 first/last 소실 문제가 소멸한다.
- `latency_ms`가 시도별로 측정되어 백오프 오염이 사라진다. 백오프는 인접 행의
  `created_at` 간격으로 관측된다.

### B안 — 한 행에 요약

```sql
attempt_count      SMALLINT
first_error_code   VARCHAR(50)
total_input_tokens INTEGER
total_output_tokens INTEGER
```

- 행 증가 0. 기존 쿼리·인덱스·`findLogIdByCorrelation` 전부 무손상.
- 시도별 지연/토큰/오류 순서는 여전히 복원 불가. `latency_ms` 백오프 오염도 그대로.
- `input_tokens`와 `total_input_tokens`가 공존해 "어느 쪽이 진짜 비용인가"라는 원래의
  모호함이 컬럼 이름 수준으로 이동할 뿐 해소되지 않는다.

### 권고: A안

B안은 이번에 고치려는 세 문제 중 1번만 해결하고 2번을 부분적으로만 해결한다(횟수는
알지만 어느 시도가 무엇을 소비했는지는 모른다). 아래 행 증가량 추정대로 A안의 비용이
낮으므로, 정보를 버리는 쪽을 택할 근거가 약하다.

## 행 증가량 추정

**아래 숫자는 전부 추정이며 실측 데이터가 아니다.** 재시도율 실측치가 없으므로 가정을
명시한다. W4-8 실데이터로 재확인 후 이 절을 갱신한다.

가정:

1. 재시도는 Claude 경로에서만 발생한다 (STT/TTS 재시도 코드 없음 — 검증됨).
2. LLM 호출이 전체 로그 행에서 차지하는 비중을 **약 50%** 로 본다. 근거:
   `AI_PIPELINE.md:246`의 S12 턴 예시가 4행(STT 1, LLM 2, TTS 1)이고 S07은 1행 전부
   LLM이다. **추정, 실측 트래픽 믹스로 검증 필요.**
3. 재시도 1회당 행 1개 증가. 429의 2회 재시도는 드물다고 보고 무시한다.

| 가정 재시도율 (LLM 호출 기준) | 전체 행 증가율 |
| --- | --- |
| 2% | 약 +1% |
| 5% | 약 +2.5% |
| 10% | 약 +5% |

재시도율이 10%를 넘으면 행 증가보다 **AI 품질 자체가 먼저 문제**다. 즉 A안의 저장
비용은 "정상 운영 구간에서 무시 가능, 비정상 구간에서는 그 비정상을 드러내는 신호"다.
v1 트래픽 규모에서 절대 행 수 자체가 작으므로 파티셔닝·보존정책은 이번 범위 밖.

## 기존 인덱스 4개 유효성

| 인덱스 | A안 이후 | 조치 |
| --- | --- | --- |
| `idx_logs_feature_date (feature_name, created_at DESC)` | 유효. 반환 행이 재시도만큼 늘지만 비용 집계는 전 행을 원한다 | 유지 |
| `idx_logs_correlation (request_correlation_id)` | 유효. 그룹당 행이 늘 뿐 | 유지 |
| `idx_logs_user_date (user_id, created_at DESC) WHERE user_id IS NOT NULL` | 유효 | 유지 |
| `idx_logs_status_date (status, created_at DESC) WHERE status != 'success'` | 유효하며 **개선**. 재시도로 복구된 중간 실패가 이제 이 인덱스에 잡힌다 — 새 알림이 노리는 바로 그 행 | 유지 |

신규 1개:

```sql
CREATE INDEX idx_logs_attempt_group ON ai_request_logs(attempt_group_id, attempt_number);
```

논리적 호출 단위 롤업(그룹당 총비용, 시도 시퀀스 복원)과 최종 시도 조회에 필요하다.
`is_final_attempt` 단독 인덱스는 추가하지 않는다 — 최종 시도가 전체의 90%대라 선택도가
낮아 기존 `(feature_name, created_at)` 스캔 대비 이득이 없다(추정, EXPLAIN으로 확인).

## 결정 사항

### prompt_version 문제

**컬럼을 추가하지 않는다.** reminder 부착 여부는 같은 `attempt_group_id` 안에서
`attempt_number > 1 AND 직전 시도의 error_code = 'schema_validation_failed'` 로
결정론적으로 유도된다(`AnthropicService.java:93-96`의 조건 그대로). 유도 가능한 사실에
컬럼을 다는 것은 CLAUDE.md "decision-logged > implementation-pre-specified" 위반이다.

대신 `AI_PIPELINE.md`에 유도 규칙을 한 문장으로 명시한다: `prompt_version`은 **로드된
프롬프트 파일의 버전**이며, 재시도 시 덧붙는 constraint reminder는 프롬프트 버전을
바꾸지 않는다.

### timeout 시 토큰 기록

응답이 없으므로 실제 토큰을 알 수 없다. 프롬프트 문자 수로 추정하지 **않는다** — 검증
불가능한 숫자를 비용 컬럼에 넣으면 `estimated_cost_usd` 전체의 신뢰도가 떨어진다.

대신 **NULL과 0의 의미를 분리**한다. 이것이 이 계획의 핵심 계약 하나다:

- `NULL` = 값을 알 수 없음 (timeout, provider가 `usage`를 누락한 경우)
- `0` = 값이 0임을 앎 (cache_hit)

`SUM()`은 NULL을 무시하므로 비용 합계는 **하한**이다. 이 사실을 문서에 명시하고,
NULL 비용 행의 비율을 관측 가능하게 둔다(그 비율이 커지면 집계 신뢰도 경고).

### cache_hit의 estimated_cost_usd

**0으로 정의한다.** 코드가 이미 그렇게 동작하므로(`TtsPlaybackService.java:250`) 새 결정이
아니라 문서-코드 드리프트 해소다. `latency_ms`도 실측값을 그대로 기록한다.

### estimated_cost_usd의 단위 — 문서에 박을 문장

> `estimated_cost_usd` records the cost of **that one attempt**, never a roll-up.
> The cost of a logical call is `SUM(estimated_cost_usd)` grouped by `attempt_group_id`;
> total spend is `SUM(estimated_cost_usd)` with no attempt filter at all. A `NULL` means
> the cost is unknown (no usage returned), not zero — sums are therefore a lower bound.
> Only `cache_hit` rows carry a known `0`.

`input_tokens`/`output_tokens`/`latency_ms` 전부 동일하게 **시도별 값**이다.

### correlation 2단 계층

`request_correlation_id`(사용자 행동) > `attempt_group_id`(논리적 호출) 채택. 단
**현재 S07에서는 두 레벨이 1:1**이다 — `AnalysisService.java:123`이 LLM 호출 직전에
correlation을 만들기 때문. 문서(`AI_PIPELINE.md:230`)의 "user action 시작 시 생성"과의
불일치는 **이번 범위에서 고치지 않고 별건으로 남긴다**(S12 다중 호출 경로를 건드려야
하므로 위험도가 다르다). 계층 구조 자체는 지금 도입해도 S12에서 그대로 의미를 갖는다.

### 알림 재정의 (architecture.md)

기존 1개를 2개로 나눈다.

- **사용자 영향 알림(기존 의미 유지)** — `WHERE is_final_attempt` 기준 실패율 > 5% /
  10분 / feature_name. 실제로 사용자가 에러를 본 비율.
- **열화 조기경보(신규)** — 전 시도 기준 실패율. 재시도로 복구된 실패를 포착한다.
  임계값은 **TBD** — 실측 기준선 없이 숫자를 만들지 않는다. W4-8 실데이터로 설정한다.

### PRD 지표 측정 가능화

"JSON schema failure rate"를 다음으로 정의한다:

```
schema_validation_failed 를 가진 attempt_group 수 / LLM 논리적 호출 수
```

= `COUNT(DISTINCT attempt_group_id) FILTER (WHERE error_code = 'schema_validation_failed')`
÷ `COUNT(*) FILTER (WHERE is_final_attempt AND prompt_version IS NOT NULL)`.
A안 이전에는 분자가 0에 가깝게 관측된다(복구된 실패가 안 남으므로).

## Files expected to change

STEP 2 (문서):

- `docs/data-model.md` — DDL 3컬럼 + 인덱스 1개, 컬럼 설명, `:469` "writes one row" 문장
- `docs/AI_PIPELINE.md` — Logging contract 재작성(`:221` "exactly one row" 폐기),
  실패 모드별 기록 방식 표 추가, NULL/0 계약, prompt_version 유도 규칙
- `docs/architecture.md:313` — 알림 2개로 분리
- `docs/PRD.md:42` — schema failure rate 정의 명시
- `SECURITY.md:50-53` — `ai_request_logs` 저장 컬럼 나열에 신규 3컬럼 반영(전부 메타데이터,
  원문 없음 — 프라이버시 보증 불변)

STEP 3 (ADR): `docs/decisions/011-*.md` + `INDEX.md` 한 줄

STEP 4 (코드, **owner 승인 필수**):

- `backend/src/main/resources/db/migration/V008__ai_request_logs_attempt.sql`
- `AiRequestLogEntry.java` — 3필드 + 빌더 + 검증
- `JdbcAiRequestLogStore.java` — INSERT 컬럼
- `AnthropicService.java` — 루프 안에서 시도마다 로깅, 시도별 latency 측정
- `TranscriptionService.java` / `TtsPlaybackService.java` — 단일 시도이므로
  `attempt_number=1, is_final_attempt=true, attempt_group_id` 세팅만
- `JdbcAnalysisRepository.java:134` — `AND is_final_attempt` 추가

## Acceptance criteria

1. 1차 성공: 행 1개, `attempt_number=1`, `is_final_attempt=true`, 토큰·비용 채워짐.
2. 재시도 후 성공: 행 2개, 같은 `attempt_group_id`, `attempt_number` 1·2,
   `is_final_attempt` false·true. 1행에 `error_code` 존재.
   `SUM(estimated_cost_usd)`가 두 시도 합.
3. 2회 실패: 행 2개 모두 실패, 2행만 `is_final_attempt=true`. **각 행이 자기
   `error_code`를 가진다** (혼합 시퀀스에서 첫 오류가 소실되지 않는다).
4. timeout: 행 1개, 재시도 없음, 토큰·비용 NULL(0 아님).
5. cache_hit: 행 1개, `estimated_cost_usd = 0`, `attempt_number=1`.
6. `WHERE is_final_attempt` 로 센 행 수 = 마이그레이션 이전 의미의 행 수.
7. V008 적용 후 기존 행 전부 `attempt_number=1, is_final_attempt=true,
   attempt_group_id = id` (과거 행은 각각이 논리적 호출 1개였으므로 자기 id가 정확).
8. `./gradlew spotlessApply spotlessCheck test build` 통과.

## Test plan

TDD. `AnthropicServiceTests`에 fake Sleeper로 위 1~5 시나리오를 각각 고정하고, 기록된
행 수·`attempt_number`·`is_final_attempt`·`error_code`·비용 합을 단언한다.
`JdbcAiRequestLogStoreIntegrationTest`에 신규 컬럼 왕복(round-trip)과 NOT NULL 제약을
추가한다. `FlywayMigrationTests`/`MigrationSqlStructureTests`에 V008 구조를 추가한다.
`JdbcAnalysisRepositoryTests`에 "같은 correlation에 2행이 있을 때 최종 시도 id를
반환한다"를 추가 — A안이 깨뜨리는 지점을 회귀로 고정한다.

## Risk areas

- **비용 집계 회귀가 가장 큰 위험.** 필터를 잘못 넣은 대시보드 쿼리는 조용히 틀린 숫자를
  낸다. 완화: 문서에 "비용은 필터 없이 SUM" 문장을 박고, 인수 조건 2·6으로 고정.
- **DB 스키마 마이그레이션 — SECURITY.md "Approval required".** STEP 4는 owner 승인 전
  실행 금지.
- **AI 비용 표면 변경 — SECURITY.md "Changes that affect AI cost".** 호출 횟수는 바뀌지
  않지만 비용 *측정*이 바뀌므로 동일 승인 경로로 다룬다.
- `findLogIdByCorrelation` 미수정 시 `analysis_requests.ai_request_log_id`가 재시도
  상황에서 잘못된 행을 가리킨다. 인수 조건에 포함됨.
- 프라이버시 불변: 신규 3컬럼 전부 메타데이터. `AiRequestLogEntry`의 구조적 보증
  (원문 담을 필드 없음) 그대로 유지.
- 위험 변경이므로 CLAUDE.md section 9 두 에이전트 리뷰 적용 — 구현과 다른 에이전트가
  `docs/reviews/`에 검토 기록.

## Decision log

- **A안 채택.** B안은 세 문제 중 1번만 온전히 해결하고, 행 증가 회피라는 이득이
  추정 +1~5% 수준이라 정보 손실을 정당화하지 못한다.
- **prompt_version에 컬럼 추가 안 함** — 유도 가능한 사실이므로.
- **timeout 토큰 추정 안 함** — 검증 불가 숫자를 비용 컬럼에 넣지 않는다. NULL/0 의미
  분리로 대체.
- **cache_hit = 0** — 신규 결정이 아니라 코드 현행 동작의 문서화.
- **correlation 문서-코드 불일치는 별건 분리** — S12 경로 변경이 필요해 위험도가 다르다.
- **조기경보 임계값 TBD** — 실측 기준선 없이 숫자를 만들지 않는다.
- **행 증가율·LLM 행 비중은 전부 추정** — W4-8 실데이터로 재확인 필요.

## Final outcome

STEP 1~4 완료 (2026-08-02). A안 채택, owner 마이그레이션 승인 후 구현.

`./gradlew spotlessCheck test build --no-daemon` BUILD SUCCESSFUL (389 tests).

**단, 로컬에 Docker가 없어 Testcontainers 테스트가 전부 skip됐다.** 실행되지 않은 것:

- `JdbcAiRequestLogStoreIntegrationTest` — 6/6 skip (신규 2건 포함)
- `JdbcAnalysisRepositoryTests` — 11/11 skip (`findLogIdByCorrelation` 최종시도 회귀 포함)
- `FlywayMigrationTests` — 1/1 skip

따라서 **V008이 실제 PostgreSQL에 적용된 적은 없다.** 인수 조건 1~5(단위 테스트)와 8은
검증됐고, 6~7(마이그레이션 백필, is_final_attempt 카운트 동등성)은 미검증이다.
`.github/workflows/lint-test.yml`의 backend job이 ubuntu-latest에서 같은 명령을 돌리므로
PR 시 CI에서 실행된다 — 병합 판단은 그 결과를 보고 내려야 한다.

## What changed after execution

- **재시도 성공 경로의 토큰 손실이 계획보다 컸다.** 계획 단계에서는 "실패 시도 토큰 누락"을
  한 덩어리로 봤지만, 구현하며 갈렸다. schema 실패는 응답이 도착했으므로 **토큰이 존재하고
  청구된다**. 5xx/network/429는 응답이 없어 애초에 알 수 없다. 원래 코드는 catch 블록에서
  response를 버려 schema 실패 토큰을 복구할 수 없었으므로, 응답 수신 직후 토큰을 변수에
  담아 catch까지 살리도록 바꿨다. `AI_PIPELINE.md` 실패 모드 표와
  `schemaFailureRetryKeepsTheFailedAttemptsTokensInCost` 테스트가 이걸 고정한다.
- **백오프-created_at 관계를 문서에서 철회했다.** 계획에는 "백오프가 인접 행의 created_at
  간격으로 관측된다"고 썼는데, 구현에서 interrupt 시 `is_final_attempt`가 그룹에 하나도
  없게 되는 구멍이 나왔다. sleep 이후에 행을 쓰도록 순서를 바꿔 불변식을 지키는 대신
  created_at 간격의 의미를 포기했다. 백오프는 fallback matrix에서 결정론적으로 유도되므로
  저장 가치가 낮다는 판단. `data-model.md` 해당 문장 수정함.
- **privacy 가드 테스트가 정확히 의도대로 걸렸다.** `AiRequestLogEntryTests`의 record
  component 화이트리스트가 신규 3필드에서 실패 → 의식적 리뷰를 강제했다. 카운터·식별자뿐이라
  보증 유지, 사유를 주석으로 남기고 화이트리스트 갱신.
- **단일 시도 호출자는 코드 변경이 0이었다.** 빌더가 attempt 필드를 기본값(1/true/새 UUID)으로
  채우게 해서 `TranscriptionService`/`TtsPlaybackService`를 건드리지 않았다. 계획의 "세팅만
  추가" 항목이 불필요해진 것.

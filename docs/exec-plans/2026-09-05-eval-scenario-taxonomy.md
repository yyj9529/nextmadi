# S07 eval 분류표 고정과 케이스 22 → 74 확장, 심판 모델 분리

- 티켓: 없음 (오너 요청, 2026-09-05 세션)
- 스펙: `docs/EVAL_PLAN.md` Tier 1 ("Scenario coverage taxonomy" 절 신설)
- 브랜치: `eval/scenario-taxonomy-and-cases` (base = `origin/main`)
- 선행 PR: #160 (baseline 케이스 집합 불일치 감지). 이 브랜치와 독립이며, baseline
  재생성은 둘 다 머지된 뒤 1회 수행한다.

## 배경

오너가 eval 22문제로는 "미국 생활 전반"을 검증하기에 부족하다고 판단했다. 감사 결과
부족의 원인은 개수가 아니라 구조였다:

- 장면 분류표가 문서 어디에도 없었다. 6개 카테고리는 `test_cases.json`에만 있었고
  `EVAL_PLAN.md`는 "등등"으로 끝났다.
- 22건 전부 "한국어 상황 → 뭐라고 말하지?" 한 형태. "이렇게 말했는데 괜찮았어?",
  "문장 고쳐줘", 영어·혼합 입력, 경계 입력이 0건.
- 22건 중 18건이 정중/조심 계열 말투. 제품의 핵심 고통(할 말을 못 함)과 반대 방향.
- 생성 모델과 심판 모델이 같은 `claude-sonnet-4-6`. 자기채점 위험이 문서화되지 않음.

## 결정 (오너 승인, 2026-09-05)

| 항목 | 결정 | 근거 |
|---|---|---|
| 케이스 수 | 70~80 (결과 74) | Anthropic: 실제 실패에서 뽑은 20~50이 좋은 출발, 소수 문항은 신뢰구간이 넓음. Hamel: 행렬로 시작해 커버리지가 성장을 결정. 개수가 아니라 행렬 빈칸이 목표 |
| 분류표 | 생활 영역 14 x 화행 13 x 입력 형태 4 | USCIS M-618, CASAS, Pew, KACF, 화용론 연구. 출처는 EVAL_PLAN에 URL과 확인일 |
| 심판 모델 | `claude-opus-5` (생성은 Sonnet 4.6 유지) | Anthropic 공식: 심판은 다른 모델. 단가 $5/$25 per MTok, platform.claude.com 2026-09-05 확인 |
| 이진 채점 전환 | 보류 | judge_prompt·임계값·baseline 전부 재설계 필요. 실사용 라벨 생기면 재검토 |
| 문서 | 틀린 곳만 패치 | 전면 재작성은 시간 함정 |

## 구현

### 문서
- `docs/EVAL_PLAN.md`: 분류표 3축 표, 우선 셀 8개, 커버리지 규칙, 출처. 케이스 필드
  `category` → `domain`/`act`/`input_mode`. 심판 분리 규칙과 2026-06~09 자기채점 구간
  명시. CI 설명을 실제 artifact 비교 방식으로 정정. 비용 문장을 공식으로 교체.
- `docs/AI_PIPELINE.md`: Opus 5 단가 행 추가(eval 심판 전용), 라우팅 표 timeout 30s → 60s.
- 오류 패치: `§` 12곳 제거(architecture, data-model), START_HERE 상태 블록 2026-09 기준,
  PRD와 화면 스펙의 "W4 전 확정" TBD를 구현 기준으로 해소하거나 "open"으로 정정.

### eval
- `test_cases.json`: 기존 22건에 3축 태그 부여, 52건 추가(s07_023~074).
- `judge_prompt.md`: judge-v2. 루브릭 본문 동일, 모델만 변경.
- `run_eval.py`: `JUDGE_MODEL`, `PRICE`, 결과 artifact에 3축 기록.
- `test_cases_schema.py`: API 호출 없는 결정적 검사. 코드 유효성, id 유일성, 500자 상한,
  영역·화행 최소 2건, 입력 형태 4종 존재. #160의 CI 단위테스트 단계가 자동으로 집어 든다.

## 게이트 결과 (2026-09-05, 로컬)

- `python -m unittest discover -s eval/s07-analysis -p 'test_*.py'`: 7 tests OK
  (`test_cases_schema.py` 7건. `test_check_regression.py`는 #160 브랜치에만 있어 여기선 미포함).
- `test_cases.json` UTF-8 디코드와 한글 포함 검사 통과, 74건, id 유일.
- 커버리지: 영역 최소 2건(finance, job_search, community), 화행 최소 2건(apologize,
  bad_news, compliment), 우선 셀 8개 각 3건 이상이며 각각 "초안이 괜찮은 check_it" 1건 포함.
  새 52건의 입력 형태: say_it 26 / check_it 14 / fix_it 8 / robustness 4.
  단호·거절 계열 tone_intent 29건, 정중 계열만 5건, null 5건.
- 모델 호출 eval은 실행하지 않았다. baseline 재생성은 "남은 일" 1번.
- `§` 전수 검색: `CLAUDE.md`의 금지 규칙 문장 외 0건.

## 남은 일 (이 PR 범위 밖)

1. #160과 이 PR 머지 후 `python eval/s07-analysis/run_eval.py` 1회 실행, artifact를
   `git add -f`로 승격. 그 전까지 eval CI는 `stale_baseline`으로 실패하는 것이 정상.
2. 빈 입력·주제 이탈·프롬프트 주입 케이스는 프롬프트 v1에 처리 규칙이 없어 제외했다.
   프롬프트 v2에서 규칙을 정한 뒤 `robustness`로 추가한다.
3. 문서 감사에서 발견한 별개 버그: S06 재시도 시 BFF가 Idempotency-Key를 매번 새로
   만들어 중복 분석이 생길 수 있음 (`docs/screens/s06.md` 재시도 항목). 별도 티켓.
4. ADR-007 본문의 portfolio 문구(28, 39, 45행)는 오너 판단 대기.
5. "before W4" 잔여: `docs/AI_PIPELINE.md:375`, `docs/architecture.md:167`,
   `docs/auth.md:29`, `docs/DECISION_BACKLOG.md:6`, `docs/decisions/010:13,49`.

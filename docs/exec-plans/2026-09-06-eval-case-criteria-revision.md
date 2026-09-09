# S07 eval 74문제 정답 기준 재검토와 수정 계획

- 티켓: 없음 (오너 요청, 2026-09-06 세션)
- 대상: `eval/s07-analysis/test_cases.json` (74건), `judge_prompt.md`, `run_eval.py`, `docs/EVAL_PLAN.md`
- 브랜치: `eval/scenario-taxonomy-and-cases` 위에서 후속 작업. 이 문서는 계획만 담고 파일은 아직 고치지 않았다.
- 입력: Codex의 74건 검토 결과(2026-09-06), 오너가 정리한 Nasim 논문 해설, 그리고 이 세션에서 74건 전체와 프롬프트 v1, 판정 프롬프트, 집계 코드를 직접 대조한 결과.

## 0. 참고문헌 (전부 원문 접근 확인, 2026-09-06)

| 약칭 | 문서 | URL |
|---|---|---|
| Nasim | Nasim 외, "Do LLMs Use Cultural Knowledge Without Being Told? A Multilingual Evaluation of Implicit Pragmatic Adaptation", arXiv 2604.17718, 2026-04-20 | https://arxiv.org/abs/2604.17718 (본문 https://arxiv.org/html/2604.17718) |
| Park | Park·Trisnadi, "The transition of legal status among Korean immigrants in the United States", J. Migration and Health, 2025-09-20 | https://pmc.ncbi.nlm.nih.gov/articles/PMC12508842/ |
| Lin | Lin·Ngo·Chen, "Comparative Analysis of LLM-Based Writing Tools for Error Correction and Feedback", English Teaching & Learning, 2026-06-08 | https://link.springer.com/article/10.1007/s42321-026-00236-4 |
| Anthropic | "Demystifying evals for AI agents", 2026-01 | https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents |
| Indeed | "Bootstrap Confidence Intervals for LLM Evaluation", 2026-07-08 | https://engineering.indeedblog.com/blog/2026/07/bootstrap-confidence-intervals-for-llm-evaluation/ |

주의: Park·Trisnadi는 같은 24명 인터뷰(2023년 3~7월, SF 베이)로 논문을 세 편 냈다. 3.4.2.1 Respect toward elders, 3.4.2.2 Friendly strangers가 들어 있는 것은 위 J. Migration and Health 논문 하나다. BMC Psychology(2025-09-26)와 BMC Public Health(2025-11-26) 논문에는 그 절이 없다.

## 1. 우리 eval이 재야 하는 것 (판단 기준)

`PROJECT_CONTEXT.md`의 사용자 고통 5개와 제품 원칙에서 eval의 우선순위를 뽑으면 다음 넷이다.

1. **말 못 한 순간의 복구 전략** (고통 3 "비언어 생존"): 다시 말해달라고 하기, 부분 이해 표현하기. 분류표의 `clarify` 화행.
2. **과소 주장의 교정** (고통 2, 4): 거절·이의 제기·협상에서 물러서지 않기. `refuse`/`assert`/`negotiate`.
3. **자책을 키우지 않기** (제품 원칙 1): 이미 잘 말한 문장을 괜히 고치지 않기, "Sorry, my English is bad"를 정답으로 만들지 않기.
4. **ChatGPT 대비 우위** (고통 5): 사용자가 말투를 명시하지 않고 상황만 던져도 관계와 맥락을 읽어 맞는 표현을 내기.

현재 74건은 1~3을 잘 반영한다. 4는 거의 시험하지 않는다 (섹션 4 참조).

## 2. Codex 지적 검증 결과

Codex의 지적 8건을 원문과 대조했다. 판정: 동의 6, 부분 동의 2, 반대 0.

| Codex 지적 | 내 판정 | 근거 |
|---|---|---|
| 072 마감일 미제공 | 동의 | 입력에 원래 마감이 없다. 단, 마감이 금요일이면 `cannot finish until Friday`는 실제로 뜻이 뒤집히므로(까지→until 전이 오류) 기대행동 자체는 옳다. 입력에 마감을 넣으면 해결 |
| 029 age implied | 동의 | 딸이라는 말만으로 나이를 알 수 없다 |
| 049 미팅 날짜 요구 | 동의 | 입력에 날짜 없음 |
| 065 시작일 이유 요구 | 동의, 추가 | 062는 "사실을 지어내지 않기"를 요구하고 065는 이유를 지어내라고 요구한다. 문제집 내부 충돌 |
| 057 "인사이지 진짜 질문 아니다" | 부분 동의 | 우편함 상황에서는 타당한 설명이다. 다만 필수 정답으로 두면 절대 규칙처럼 가르치게 되므로 "이 상황에서는"으로 한정 |
| 059 title first "never wrong" | 동의 | "안전한 기본값"으로 완화 |
| 065 "철회는 드물다" | 동의 | 빈도 주장의 출처가 없다. Nasim 3.1은 대화 평가축이지 채용 결과 조사가 아님 |
| 잘못 고침을 별도 집계 | 동의 | 섹션 3-C 참조 |

## 3. 수정 계획 (우선순위 순)

### A. 스키마가 담을 수 없는 행동을 요구하는 check_it 14건 (Codex가 놓친 것, 1순위)

**발견.** `check_it` 14건의 기대행동 첫 줄은 대부분 "Output confirms the sentence was natural" 또는 "Output identifies that 'Okay' was not adequate"다. 그런데 `s07_analysis_v1` 스키마에는 판정을 담을 필드가 없다. 표현 3개와 팁만 있다. 프롬프트 v1은 "사용자가 한국어로 상황을 설명한다 → 표현 3개를 내라"만 지시하고, 초안을 평가하라는 규칙이 없다.

즉 모델이 프롬프트를 완벽히 따라도 "확인해 줬다/문제를 짚었다"는 기준은 채울 수 없다. 어제 exec-plan은 "빈 입력·주제 이탈은 프롬프트 v1에 규칙이 없어 제외했다"고 썼는데, 같은 논리를 check_it과 robustness(029 영어 입력, 070 혼합 입력)에는 적용하지 않았다.

**이유 (출처).** Anthropic Step 2 "Write unambiguous tasks with reference solutions": 모든 문제에는 채점기를 통과하는 참조 답이 있어야 하고, 그것이 문제가 풀 수 있는 문제임을 증명한다. check_it 14건은 현재 스키마로 참조 답을 쓸 수 없다. → https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents

**수정 방향 두 가지. 오너 결정 필요.**

| 선택 | 내용 | 비용 |
|---|---|---|
| A-1 (권장) | 기대행동을 관측 가능한 것으로 다시 쓴다. "variant 1이 원문과 거의 같다", "cultural_tip이 그 문장이 정상이라고 말한다", "어떤 variant도 사과를 덧붙이지 않는다". "Output confirms..."류 문장은 삭제 | 케이스 파일만 수정. 스키마·화면 변경 없음 |
| A-2 | 스키마 v2에 `draft_verdict` 필드를 추가하고 S07 화면에 표시 | API 계약·화면 스펙·프롬프트 v2 동시 변경. 이번 범위를 넘는 제품 결정 |

A-1로 가더라도 프롬프트 v2에 "입력에 영어 초안이 있으면 variant 1은 그 초안의 최소 수정본으로 하고, 초안이 이미 자연스러우면 그대로 둔다"는 규칙이 필요하다. 그 전까지 check_it 점수는 프롬프트 v1의 한계를 재는 것이고, 그것은 eval의 정상 기능이다.

### B. 입력에 없는 사실을 요구하는 기준 4건 (Codex 지적, 동의)

| 케이스 | 수정 |
|---|---|
| 072 | 입력에 "마감이 금요일인데 다음 주 화요일에나 끝날 것 같아"처럼 원래 마감과 예상일을 넣는다. 그러면 `until Friday` 반전 지적이 성립한다 |
| 049 | 입력에 미팅 날짜를 넣거나, 기대행동을 "날짜 자리에 [날짜] 같은 빈칸을 두는 것도 인정"으로 바꾼다 |
| 065 | "brief reason" 기대행동을 삭제한다. 062의 "사실을 지어내지 않기"와 충돌하기 때문. 이유 없이도 정중하게 미룰 수 있다 |
| 029 | 입력을 "my 4-year-old daughter"로 고치거나 "age implied"를 뺀다 |

같은 부류의 경미한 건: 035 "specific deadline"(입력에 없음 → "기한을 제안한다"로 완화), 034 "account reference"(자리표시자 허용 명시), 010 "tone_label is generated dynamically"(출력 하나로는 판정 불가 → 삭제).

**이유 (출처).** Anthropic Step 2, 위와 같음. 지시를 정확히 따른 답이 숨은 조건 때문에 떨어지면 안 된다.

**판정 프롬프트에 한 줄 추가.** "입력에 없는 구체 정보(날짜·계좌번호·이름)는 `[date]`류 자리표시자로 두는 것이 정답이며, 지어낸 값은 accuracy 감점." 이 규칙은 062의 "does not invent facts"를 전체 규칙으로 승격하는 것이다.

### C. 좋은 초안을 잘못 고친 실패를 별도 집계 (Codex 지적, 동의. 구현안 추가)

**발견.** 초안이 이미 괜찮은 check_it 8건(024, 028, 032, 038, 042, 048, 053, 057)은 제품 원칙 1(자책 키우지 않기)의 직접 시험이다. 그런데 `evaluate_pass`는 전체 평균 4.0, 차원 평균 3.5, 최저 2.0만 본다. 8건 중 5건에서 모델이 멀쩡한 문장을 고쳐도 accuracy 3점쯤 받으면 평균에 묻힌다.

**수정.**
1. `test_cases.json`의 check_it 케이스에 `draft_quality: "good" | "flawed"` 필드를 추가한다 (`test_cases_schema.py`에 검사 추가).
2. 판정 출력에 `draft_handling: "kept" | "rewritten" | "n/a"` 한 필드를 추가한다. 판정 프롬프트에 정의 한 문단.
3. `run_eval.py` artifact에 두 숫자를 기록한다. false_alarm = good인데 rewritten, missed_flaw = flawed인데 kept. 통과 기준에 "false_alarm ≤ 1/8" 추가 여부는 baseline 1회 본 뒤 결정 (지금 임계값을 정하면 근거 없는 숫자다).
4. artifact에 `input_mode`별 차원 평균도 같이 적는다. 이미 케이스마다 3축 태그가 저장되므로 집계 한 줄이다.

**이유 (출처).** Lin, Methodology → Data Analysis: 문법적으로 맞는 구간을 모델이 지적하면 false alarm, 그대로 두면 accurate non-intervention으로 분리 집계. 이 연구는 문법 교정 연구라 분류 원칙만 빌리고, 우리 8개 문장의 사회적 적절성 판단은 오너가 직접 확인해야 한다. → https://link.springer.com/article/10.1007/s42321-026-00236-4
Anthropic Step 3 "Build balanced problem sets": 해야 할 때와 하지 않아야 할 때를 함께 시험하지 않으면 한쪽으로 최적화된다. 문제를 넣는 것과 그 실패를 게이트가 잡는 것은 별개다.

### D. 문화적 단정 완화 4건 (Codex 3건 + 1건 추가)

| 케이스 | 현재 | 수정 |
|---|---|---|
| 057 | "'How's it going' is a greeting, not a real question" | "지나가며 하는 이 상황에서는 인사로 기능하고 짧은 답과 되묻기가 관례" |
| 059 | "using the title first is never wrong" | "title로 시작하는 것이 안전한 기본값이고 상대가 이름을 제안하면 따른다" |
| 065 | "rarely leads to withdrawal" | 빈도 주장 삭제. "오퍼 후 조건 협상은 정상적이고 예상되는 단계" |
| 071 (추가) | "a single apology plus a slightly larger tip is the normal gesture" | 팁 액수 처방 삭제. "서버는 일상적인 일로 여기며 한 번의 사과로 충분" |

**이유 (출처).** Park 3.4.2.2 Friendly strangers는 "미국 사람들은 덜 조심스럽고 더 친근하다"는 이민자의 체감을 기록한 것이고, 표현 규칙을 제시하지 않는다. Park 3.4.2.1 Respect toward elders도 호칭에서 느끼는 차이를 기술한다. 기술(description)을 규범(rule)으로 승격하려면 별도 근거가 필요하다. → https://pmc.ncbi.nlm.nih.gov/articles/PMC12508842/
제품 방향: `CLAUDE.md`의 "generic advice가 target context와 충돌하면 target이 이긴다"의 역방향이다. 우리 팁은 "미국은 무조건 이렇다"가 아니라 "이 관계·상황에서는 이 표현"이어야 하고, 그것이 프롬프트 v1 규칙 5(WHO와 WHEN)와도 일치한다.

### E. 말투를 말하지 않은 입력으로 맥락 판단을 시험 (Nasim 적용, 새 케이스 8~10건)

**발견.** 74건 중 `tone_intent`가 null인 것은 7건(003, 010, 029, 048, 054, 057, 070)뿐이고, 그중 상황 단서로 말투를 추론해야 하는 것은 거의 없다. 나머지 67건은 사용자가 "정중하게", "단호하게"를 직접 말해준다. Nasim의 용어로 74건 대부분이 Prompt B(명시적 지시)다.

실제 사용자는 상황을 설명하고 말투는 말하지 않는 경우가 많다(Prompt C). 오너의 정리대로, PhraseLog의 가치는 사용자의 Prompt C 입력을 Prompt B 품질로 바꾸는 것이다. 그런데 eval은 그 변환을 재지 않는다. 이것이 섹션 1의 우선순위 4가 비어 있는 이유다.

**수정.** 우선 셀 8개에서 각 1건씩 기존 케이스의 쌍둥이를 만든다. 같은 상황, 같은 관계 단서, `tone_intent`만 제거하고 입력에서도 말투 요구 문구를 뺀다. 예: 041 쌍둥이 = "매니저가 준 마감이 현실적으로 불가능해. 어떻게 말하지?" 기대행동은 원본과 같다 (상사에게 정중하지만 물러서지 않는 표현이 variant 1). 케이스 필드에 `pair_of: "s07_041"`를 추가하고, artifact에 쌍별 `tone_match` 차이를 적는다.

Prompt A(중립 기준선)는 만들지 않는다. 우리 질문은 "시키면 하는가 vs 상황만 보고도 하는가"이고, 그 비교에는 B와 C만 필요하다.

**이유 (출처).** Nasim 3.2 Triad Evaluation Design: 같은 시나리오를 A(중립)/B(명시 지시)/C(암시 단서)로 나눠 명시 능력과 암시 능력을 분리 측정. 4.1: 평균 PCS 0.196, 즉 시키지 않으면 명시 능력의 약 5분의 1만 발현. 4.2: 권위 관련 단서(PDI 0.299)가 가장 잘 전이되고 개인-집단 축(0.120), 가족 도메인(0.099), 기관 도메인(0.155)이 약함. → https://arxiv.org/html/2604.17718
한계: 60개 합성 시나리오, 5개 언어, 한국어 미포함. 방향 근거로 쓰고 한국어 수치는 우리가 직접 재야 한다.

**채택하지 않는 것.** 오너 해설의 "PCS 12개 feature를 rubric으로 그대로 쓰자"는 제안은 보류한다. 이유: (1) 12개 feature 채점은 판정 비용을 서너 배 키운다. (2) 그 feature들은 목표 문화로의 이동량을 재도록 설계됐고, 우리 케이스는 이미 `expected_behaviors`에 방향을 케이스별로 적어 두었다. (3) 4개 차원 + 위의 쌍 비교로 같은 질문("암시 단서만으로 맞는 말투를 내는가")에 답할 수 있다. 실사용 데이터가 쌓여 방향별 통계가 필요해지면 재검토.

### F. 판정 프롬프트 보강 (A~E의 공통 기반)

1. payload에 `input_mode`, `domain`, `act`, `draft_quality`를 추가한다. 지금 판정 모델은 이 케이스가 "고쳐줘"인지 "괜찮았어?"인지 `expected_behaviors` 문장을 읽어 추측한다.
2. 자리표시자 규칙(B), `draft_handling` 필드(C)를 추가한다.
3. `judge-v3`로 올린다. 규칙 변경이므로 재baseline 대상이다 (EVAL_PLAN 미결 1번과 같은 절차). 아직 judge-v2 baseline이 없으니 지금이 비용 없이 바꿀 유일한 시점이다.

### G. EVAL_PLAN 문서 패치

1. "이 집합은 변경 전후의 짝 비교용이며, 실사용자 집단의 품질 추정치가 아니다"를 Scope에 명시한다. Indeed의 첫 가정 "N inputs are an iid sample from the target distribution"이 우리 집합에는 성립하지 않기 때문이다. 74건은 행렬을 채우기 위해 손으로 쓴 가설이고, `PROJECT_CONTEXT.md` 원칙 4대로 실사용 입력이 들어오면 교체 후보가 된다. → https://engineering.indeedblog.com/blog/2026/07/bootstrap-confidence-intervals-for-llm-evaluation/
2. 참고문헌 절에 위 표 5건을 URL·확인일과 함께 추가한다. 현재 저장소 어디에도 이 논문들이 없다.
3. Test case structure에 `draft_quality`, `pair_of` 필드를 추가한다.
4. Indeed의 부트스트랩 신뢰구간은 지금 도입하지 않는다. 현재 회귀 기준(평균 0.3 하락)은 근거 없는 고정값이지만, baseline 두 개가 생기기 전에는 신뢰구간을 계산할 대상이 없다. 두 번째 baseline이 나오면 케이스별 짝 차이에 부트스트랩을 적용해 0.3을 데이터로 대체하는 것을 검토한다.

## 4. 실행 순서와 게이트

| 순서 | 작업 | 산출물 |
|---|---|---|
| 1 | 오너 결정: A-1 vs A-2 | 이 문서 결정란 |
| 2 | B, D 케이스 문구 수정 (13건) | `test_cases.json` |
| 3 | A-1 check_it 14건 기대행동 재작성, `draft_quality` 부여 | `test_cases.json`, `test_cases_schema.py` |
| 4 | E 쌍둥이 8건 추가 (`s07_075`~`082`) | `test_cases.json` |
| 5 | F 판정 프롬프트 judge-v3, `run_eval.py` 집계 추가 | `judge_prompt.md`, `run_eval.py`, 단위테스트 |
| 6 | G 문서 패치 | `docs/EVAL_PLAN.md` |
| 7 | #160 머지 후 baseline 1회 실행 | `eval/runs/` |

게이트: `python -m unittest discover -s eval/s07-analysis -p 'test_*.py'` 통과, 74+8건 UTF-8·id 유일·커버리지 규칙 유지, 금지 섹션 기호 0건. 모델 호출은 7번에서만.

## 6. 실행 결과 (2026-09-06, 같은 세션)

오너 결정: A-1 (체크리스트 수정, 스키마·화면 변경 없음). 브랜치 `eval/case-criteria-revision`
(PR #161 브랜치 위에 쌓음).

- B, D: 072·049·029 입력 보강, 065·035·034 기준 완화, 010 관측 불가 기준 삭제, 057·059·065·071 문화 단정 완화.
- A-1: check_it 14건의 "Output confirms/identifies..." 기준을 `expressions[0].english` / `cultural_tip` 기준으로 재작성. `draft_quality` good 8 / flawed 6 부여.
- E: 쌍둥이 8건 추가 (s07_075~082, 원본 023·026·031·036·041·047·050·056). 총 82건.
- F: `judge_prompt.md` judge-v3 (payload에 3축·draft_quality·pair_of, 자리표시자 규칙, `draft_handling`). `run_eval.py`에 `by_input_mode`, `draft_summary`, `pair_deltas` 보고 항목 추가. 게이트 임계값은 추가하지 않음.
- G: `EVAL_PLAN.md` Scope 한계 문단, 필드 2개, Implicit-cue pairs 절, 보고 항목 절, References 절.
- 테스트: `test_cases_schema.py`에 3건 추가(check_it draft_quality 필수, 관측 불가 문구 금지, pair 무결성), `test_run_eval_summaries.py` 신규 5건. `python -m unittest discover -s eval/s07-analysis -p 'test_*.py'` 15건 통과. 모델 호출 없음.
- 사람 검증: check_it 14건의 draft_quality 라벨을 미국인 원어민 1명이 검토 (2026-09-06).
  결과 12건 동의. 024는 "too long"으로 flawed 전환 (문제는 길이뿐, 무례·불명확 아님을 기준에 명시).
  032는 good 유지, "약간 기계적"이라는 의견을 반영해 가벼운 다듬기는 rewrite가 아니라고 명시.
  043·064·074는 "문장 자체는 자연스럽다"는 의견을 받아, 결함이 화용적(전달된 뜻)임을 기준과
  실패 모드에 추가하고 "bad English"로 판정하면 감점. 064는 원어민 의견을 우선해 good으로 확정 (오너 결정 2026-09-06): variant 1은 초안 유지,
  전화를 끊는 단호한 표현은 추가 선택지로만 요구. 최종 good 8 / flawed 6.
- 남은 일: #160·#161 머지 후 baseline 1회 실행 (judge-v3 첫 baseline).

## 5. 이번에 확인하지 못한 것

- Codex 답변 끝의 "모델 변경 시 성능 경고"와 Codex 공식 문서 "Choose a model"은 eval과 무관해 이 계획에서 제외했고 출처도 찾지 않았다.
- 오너 해설에 나온 "Coach Mia V2.6", "PhraseLog Reviewer Template v2", "SceneBuilder 4-field V1"은 저장소 문서에 없다. Notion 쪽 자료로 보이며 이 계획은 그 내용에 의존하지 않는다.
- 위 8개 "좋은 초안"이 실제로 사회적으로 적절한지는 논문이 인증해 주지 않는다. 오너 또는 검증자 네트워크(EVAL_PLAN 미결 5번)가 사람 눈으로 확인해야 한다.


## 7. 정정 (2026-09-06, 실행으로 반증됨)

계획서 3-A의 진단 "check_it 14건은 현재 스키마로 참조 답을 쓸 수 없다"는 **틀렸다.**
근거 없이 단정한 것이고, 같은 날 실제 실행이 반증했다.

s07_024를 옛 기준과 새 기준으로 각 2회 채점했다. 학생 답(output)은 1회만 생성해
양쪽에 동일하게 사용했다.

| 기준 | naturalness | accuracy | cultural | tone | 평균 |
|---|---|---|---|---|---|
| 옛 기준 ("Output recognizes ...") | 4.5 | 4.5 | 4.5 | 5.0 | 4.62 |
| 새 기준 (관측 지점 명시 + flawed) | 3.0 | 3.0 | 4.0 | 4.0 | 3.50 |

옛 기준에서 채점관 사유: "Variant 1 preserves the user's draft verbatim and the tip
explicitly validates it, so no problem was manufactured." 즉 채점관은 "Output
recognizes ..."를 문제없이 평가했다. 옛 문장에는 세미콜론 뒤에 "variant 1 keeps it
essentially as-is"라는 관측 가능한 절이 이미 있었고, 채점관은 그것을 근거로 썼다.

또한 이 비교에서 **두 가지를 동시에 바꿔놓고 한 가지 때문이라고 설명한 오류**가 있었다.
4.62 → 3.50 하락의 원인은 문장 표현이 아니라 판단 자체가 뒤집힌 것(draft_quality
good → flawed, 원어민 지적 반영)이다. 문장 표현이 점수에 영향을 줬다는 증거는 없다.

### 이 정정에 따른 조치

- `EVAL_PLAN.md`: 해당 문단을 "readability convention, not a correctness fix"로 수정하고
  실측값(4.75/4.50)을 근거로 명시. 자리표시자 규칙은 s07_062 대 s07_065 모순이라는
  실제 사례가 있으므로 유지.
- `test_cases_schema.py`: `test_check_it_behaviors_are_observable` 삭제. 막을 만한
  피해 사례가 없는데 CI를 실패시키는 검사였다 (CLAUDE.md 3축 검증의 "실제 사고 비용"
  축 미통과).
- check_it 14건의 문구 재작성 자체는 유지. 더 명확하고 해가 없다. 다만 측정된 개선은
  주장하지 않는다.

### 실행으로 발견한 실제 버그 2건 (별개 소득)

judge를 Opus 5로 바꾼 2026-09-05 이후 한 번도 실행하지 않아 드러나지 않았던 것들이다.
이 상태로 CI가 돌았다면 82건 전부 채점 실패(전 항목 1점) 처리됐다.

1. `run_eval.py`가 `resp.content[0].text`로 첫 블록을 텍스트로 가정했다. Opus 5는 첫
   블록이 ThinkingBlock이라 `.text`가 없다 → `AttributeError`. `response_text()`를
   추가해 type == "text" 블록만 골라 잇도록 수정.
2. judge의 `max_tokens=1000`이 thinking 토큰까지 포함해 소진되어 채점 JSON이 문장
   중간에서 잘렸다 (`stop_reason=max_tokens`, output_tokens 정확히 1000) → 4000으로 상향.

### CI 불일치 1건

`EVAL_PLAN.md`는 CI 1단계에서 무료 단위 테스트가 돈다고 적었으나
`.github/workflows/eval.yml`에는 그 단계가 없었다. 시험지가 깨져도 유료 API 492회를
호출한 뒤에야 실패하는 상태였다. `Run no-API unit tests` 단계를 baseline 조회 앞에 추가.

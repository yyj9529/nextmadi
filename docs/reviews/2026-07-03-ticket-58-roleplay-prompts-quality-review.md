# Review - Roleplay Prompt v1 Set (#58)

- **Date:** 2026-07-03
- **Reviewer:** Claude Code (Opus 4.8) — quality review
- **Target:** 7 files under `prompts/roleplay/**` (merged in PR #78, 2026-06-12)
- **Related issue:** GitHub issue #58, `E10.1 Roleplay Prompt v1 Set (init/turn/feedback/result + 3 Coach Personas)`
- **Scope:** Opus 품질 축 3종 — persona 일관성, shame-reducing 피드백, 코치 발화 비율.
  Codex 담당 기계 검증(frontmatter 필드, 콜러블 schema 연결)은 2026-06-14 코멘트에서 완료됨. 본 리뷰는 그 위에 얹는 품질 판단이며 코드/파일을 고치지 않는다.
- **Verdict:** **품질상 v1 승인 가능(APPROVE for v1)**, 단 아래 조건.
  프롬프트 자체는 세 축 모두 방향이 맞다. 다만 acceptance criteria의 "코치 발화 비율 체크"와 "샘플콜 schema 검증"은 **프롬프트 텍스트만으로는 충족 불가** — 런타임 샘플콜(#28 로더 / #26 schema)이 있어야 실증된다. 따라서 **#58은 open 유지**가 맞고, 아래 P2 항목은 v1 머지 후 개선(또는 v1.1)으로 다뤄도 된다.

## 세 축 판정 요약

| 품질 축 | 판정 | 근거 |
|---|---|---|
| Persona 일관성·구별성 | PASS (경미 지적) | Mia/David/Sarah가 톤·담당 시나리오로 구별됨. 선택 축이 혼재(아래 P2-1). |
| Shame-reducing 피드백 | PASS (경미 지적) | feedback rule 6 / result rule 8이 명시적. awkward_pairs 무제한이 위험(P2-2). |
| 코치 발화 비율 (S12 게이트) | 프롬프트상 PASS / **실증 미완** | turn rule 4·6이 발화를 짧게·비지배적으로 유도하나 비율은 샘플콜로만 측정 가능(P1-1). |

## Gate — 품질 축 상세

### 축 1. Persona 일관성·구별성 — PASS

- 세 persona가 서로 다른 담당을 가짐: Mia(정서적으로 위축된 사용자), David(직장·서비스·예약·문제해결), Sarah(이웃·친구·학부모·스몰토크·관계회복). v1 수준에서 충분히 구별된다.
- 각 파일이 `voice_rules` 마지막 항목에서 "Korean encouragement in result summaries" 톤을 규정 → result 프롬프트의 `coach_encouragement`(rule 6, "in the coach's style")와 배선이 맞물린다. persona→result 연결이 끊기지 않음. 좋음.
- turn/init 프롬프트가 "stay in the coach persona"로 persona context를 참조하도록 되어 있어 구조적으로 일관.

### 축 2. Shame-reducing 피드백 — PASS

- feedback rule 6: "Do not shame the user. Avoid words like 'wrong' or 'bad' in Korean." — 명시적. 기본적으로 조용함(show_feedback false가 기본, 이해 가능한 발화는 통과)이라 교정 피로를 낮춘다. PROJECT_CONTEXT의 "대본 없이 대응하는 자신감" 철학과 정합.
- result rule 8: "Avoid shame. Emphasize what the user can try next." + S12b가 awkward_pairs를 "이렇게도 좋아요"로 긍정 프레이밍 → 수치심 회피 일관.
- feedback가 show_feedback 조건을 "듣는 사람을 혼란시킬 문법 오류 / 부자연스러운 직역 / 실질적으로 도움되는 대안이 있을 때"로 좁힌 것도 좋다.

### 축 3. 코치 발화 비율 (S12 quality-gate) — 프롬프트상 PASS, 실증 미완

- turn rule 4("Prefer 1-2 natural sentences"), rule 6("Do not dominate the conversation. Ask a natural follow-up when helpful") — 발화를 짧게 유지하고 공을 사용자에게 넘기도록 유도. init의 scenario_setup도 1-2문장 제한이라 시작부터 코치가 말을 독점하지 않는다.
- 다만 `docs/quality-gates.md` line 28의 게이트는 "coach utterance ratio **checked**"이다. 프롬프트의 정성 지침만으로는 "checked"가 아니다 — 실제 비율은 샘플 세션 출력에서 측정해야 한다. 이 축의 게이트 충족은 #26/#28 이후로 이월된다(P1-1).

## Findings

### P1-1 — 코치 발화 비율 게이트는 샘플콜 없이 닫을 수 없다 (BLOCKER for closing, not for v1 merge)

- **무엇:** acceptance criteria "Coach utterance ratio checked — coach does not dominate"와 quality-gates S12 라인은 **측정된 증거**를 요구한다. 현재는 프롬프트 지침만 존재.
- **왜 중요:** turn 프롬프트가 "1-2 문장"을 "prefer"로만 걸어둬서, 모델이 긴 설명형 발화로 흐를 여지가 남는다. 실측 없이는 게이트 통과 주장 불가.
- **권고:** #28 로더 + #26 샘플콜이 붙는 시점에 5-10턴 샘플 세션 1-2개를 돌려 코치:사용자 토큰(또는 문자수) 비율을 로깅. 목표선(예: 코치 발화가 사용자 발화 길이를 크게 넘지 않음)을 quality-gates에 수치로 못박는 것을 제안. 지금 #58에서 할 일은 아님 — open 유지 근거.

### P2-1 — persona 선택 축이 혼재되어 있다 (v1.1 개선 후보)

- **무엇:** David/Sarah는 **시나리오 도메인**(직장 vs 이웃)으로 나뉘는데, Mia는 **사용자 정서 상태**(긴장·자기비판적)로 정의된다. 축이 섞여 있어 S03b 코치 선택에서 "긴장한 사용자가 직장 롤플레이"를 할 때 Mia/David 중 어디로 가야 하는지 모호해질 수 있다.
- **영향:** 프롬프트 품질 자체보다 코치 선택 UX(#53 S03b)·coach_profiles 카피에 영향. v1 프롬프트를 막을 사유는 아님.
- **권고:** persona 파일에 "이 코치가 특히 잘 맞는 상황"과 "정서 톤"을 분리 서술하거나, 선택 화면에서 두 축을 독립적으로 안내. 결정은 owner 몫.

### P2-2 — result의 awkward_pairs / feedback가 양적 상한이 없다 (shame-reduction과 상충 가능)

- **무엇:** result rule 4는 awkward_pairs를 "0 or more"로, S12b는 "0-N"으로 둔다. recommended_expressions(0-3)와 달리 상한이 없다.
- **왜 중요:** 수치심 회피가 이 기능의 핵심인데, 한 세션에서 교정 카드가 6-8개 쏟아지면 rule 8("Avoid shame")과 정면으로 부딪친다. "이렇게도 좋아요"라는 긍정 프레이밍도 개수가 많으면 무너진다.
- **권고:** awkward_pairs에 소프트 상한(예: 최대 3, "가장 도움되는 것만")을 프롬프트에 추가. feedback도 이미 "Do not comment on every small imperfection"이 있으니 result에도 대칭적으로. v1.1 또는 지금 한 줄 추가로 가능 — owner 판단.

### N-1 — 잘된 점: S07 변이 필드 완전 일치 (검증 완료)

- result rule 3의 6개 필드(`english`, `tone_label`, `ipa`, `korean_pronunciation`, `pronunciation_tip`, `cultural_tip`)가 `AI_PIPELINE.md` S07 variant 스키마(line 122-127)와 정확히 일치. S12b에서 저장 시 expression_variants로의 매핑이 깨지지 않는다. 확인함.

### N-2 — 조율 필요: persona 파일은 콜러블 프롬프트가 아니라 context fragment

- 2026-06-14 코멘트가 이미 지적한 사항 재확인. mia/david/sarah는 `feature: roleplay_coach_persona` / `output_schema: coach_persona_context_v1`을 갖지만 이들은 `AI_PIPELINE.md`의 콜러블 feature/스키마가 아니다. #28 로더는 이 3개를 **검증 대상 콜러블 프롬프트가 아닌 fragment**로 취급해야 한다(로더 글로빙이 이들에 schema validation을 걸면 실패). #58 품질 문제 아님 — #28 구현 시 처리할 계약.

## 결론 / #58 다음 액션

1. **프롬프트 품질 v1 승인.** 세 축 방향 정합. 파일 수정 강제 사유 없음.
2. **#58은 계속 open.** 남은 acceptance criteria(로더 파싱 #28, 샘플콜 schema #26, 코치 발화 비율 실측)는 런타임이 있어야 닫힌다. 프롬프트 티켓 단독으로는 종료 불가 — 이건 스펙대로의 정상 상태이지 결함이 아니다.
3. **owner 결정 필요 2건:** P2-1(persona 선택 축 정리 여부), P2-2(awkward_pairs 상한 지금 추가 vs v1.1). 둘 다 v1 머지를 막지 않는다.
4. live eval/샘플콜/provider 호출은 owner 승인 전 실행하지 않음.

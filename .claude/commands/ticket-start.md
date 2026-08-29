---
description: 티켓 작업 시작 - git 상태 + 이슈 본문/코멘트 자동 로드 후 참조 파일 첨부
argument-hint: [issue-number]
allowed-tools: Bash(git status*), Bash(gh issue view*)
---

현재 git 상태:

!`git status --short --branch`

GitHub 이슈 #$ARGUMENTS:

!`gh issue view $ARGUMENTS --comments --repo yyj9529/nextmadi`

위 이슈를 처리한다. 진행 순서의 기준은 `docs/harness.md` 7절이다. 티켓별 배정(누가/뭘 보고)은 `docs/ai-ticket-operating-map.md`에 카드가 있을 때만 따르고, 없으면 7.1절 라우팅 표의 기본값을 쓴다. 배정을 7절의 워크플로 순서에 끼워 넣어 다음 단계를 제시하고, 매 단계 owner 확인을 받는다. 다음 순서로 진행해라.

1. `docs/ai-ticket-operating-map.md`에서 이 티켓의 카드를 찾는다. 카드가 있으면 **주 모델 / 리뷰 / 참조 파일 / 추천 도구 / 작업 단계**를 그대로 따른다. 카드는 기본값에서 벗어나는 티켓에만 존재하므로, 없는 것이 정상이다 — 없으면 `docs/harness.md` 7.1절 라우팅 표에서 이 티켓 유형에 해당하는 행의 기본값을 쓰고, 카드를 새로 쓰지 않는다.
2. 배정에 적힌 참조 파일을 읽는다. UI 티켓이면 해당 `docs/screens/sNN.md`, API/백엔드면 `docs/api/openapi.yaml` + `docs/data-model.md`, AI 파이프라인이면 `docs/AI_PIPELINE.md` + 관련 `prompts/**`.
3. `docs/solutions/README.md`(실수 대장)에서 이 티켓 유형에 해당하는 노트를 읽는다. 매핑은 `CLAUDE.md` "Context loading policy"의 실수 노트 매핑 표를 따른다. 이 저장소에서 같은 유형의 티켓이 어떤 실수를 반복했는지 알고 시작하기 위한 단계다 — 전부 읽지 말고 해당 유형만 읽는다.
4. 위험 티켓(auth/AI/DB/roleplay-state)이면 구현 전 `docs/exec-plans/`에 계획을 쓰고, 구현자와 다른 계열이 리뷰하도록 한다.
5. 카드가 지정한 모델과 다른 모델로 진행해야 할 이유가 있으면 먼저 owner에게 말한다.

live eval/provider 호출, deploy, secrets, DB migration은 owner 승인 전 실행하지 않는다.

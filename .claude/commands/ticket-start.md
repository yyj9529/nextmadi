---
description: 티켓 작업 시작 - git 상태 + 이슈 본문/코멘트 자동 로드 후 참조 파일 첨부
argument-hint: [issue-number]
allowed-tools: Bash(git status*), Bash(gh issue view*)
---

현재 git 상태:

!`git status --short --branch`

GitHub 이슈 #$ARGUMENTS:

!`gh issue view $ARGUMENTS --comments --repo yyj9529/nextmadi`

위 이슈를 처리한다. 다음 순서로 진행해라.

1. `docs/ai-native-dev-guide.md`에서 이 티켓의 카드를 찾아 **주 모델 / 리뷰 / 참조 파일 / 추천 도구 / 작업 단계**를 확인한다.
2. 카드에 적힌 참조 파일을 읽는다. UI 티켓이면 해당 `docs/screens/sNN.md`, API/백엔드면 `docs/api/openapi.yaml` + `docs/data-model.md`, AI 파이프라인이면 `docs/AI_PIPELINE.md` + 관련 `prompts/**`.
3. 위험 티켓(auth/AI/DB/roleplay-state)이면 구현 전 `docs/exec-plans/`에 계획을 쓰고, 구현자와 다른 계열이 리뷰하도록 한다.
4. 카드의 모델 배정과 다른 모델로 진행해야 할 이유가 있으면 먼저 owner에게 말한다.

live eval/provider 호출, deploy, secrets, DB migration은 owner 승인 전 실행하지 않는다.

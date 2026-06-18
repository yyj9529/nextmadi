---
description: 변경 diff를 해당 screen 스펙 G-W-T와 대조 (2-게이트 중 스펙 준수 게이트)
argument-hint: [sNN  예: s07]
allowed-tools: Bash(git diff*), Bash(git status*)
---

현재 변경분:

!`git status --short`

!`git diff --stat`

이 변경을 `docs/screens/$1.md`의 스펙과 대조한다. (`docs/quality-gates.md`의 게이트 기준 적용)

1. `docs/screens/$1.md`를 읽는다. Given-When-Then, UI 상태, edge case 목록을 추출한다.
2. 현재 diff(`git diff`)가 각 G-W-T / 상태 / edge case를 충족하는지 항목별로 ✅/❌/❓ 판정한다.
3. 누락·위반만 보고한다. 코드 품질(가독성/구조)은 여기서 보지 않는다 — 그건 2게이트의 다음 단계(code review).
4. 스펙에 TBD로 표시된 항목은 owner 확정 사항이므로 임의 판단하지 말고 "owner 확정 필요"로 표시한다.
5. UI 티켓이면 브라우저 증빙이 필요하다 — `/ui-verify`로 확인하라고 안내한다.

출력: 항목별 체크리스트 + 미충족 항목 요약. 위반이 많으면 `docs/reviews/`에 기록 제안.

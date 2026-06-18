---
name: ui-verify
description: UI 게이트 브라우저 증빙. 화면 티켓(S01~S12b) 구현 후 격리 컨텍스트에서 Playwright로 렌더/상태/콘솔 에러를 확인하고 스크린샷을 남긴다. 프론트엔드 변경 검증이 필요할 때 사용.
argument-hint: [url-or-path  예: http://localhost:3000/save/result/123]
context: fork
agent: general-purpose
allowed-tools: mcp__playwright__browser_navigate, mcp__playwright__browser_snapshot, mcp__playwright__browser_take_screenshot, mcp__playwright__browser_console_messages, mcp__playwright__browser_resize, mcp__playwright__browser_click, mcp__playwright__browser_wait_for, Read, Glob
---

`docs/quality-gates.md`의 UI 게이트 증빙을 만든다. 대상: `$1`

격리(fork) 컨텍스트에서 실행되므로 메인 작업 맥락을 더럽히지 않는다. 다음을 수행하라.

1. `mcp__playwright__browser_navigate`로 `$1`에 접속한다. (로컬 dev 서버 미기동이면 그 사실을 보고하고 중단)
2. `mcp__playwright__browser_snapshot`으로 접근성 트리를 캡처해 핵심 요소가 렌더됐는지 확인한다.
3. 해당 화면 스펙(`docs/screens/sNN.md`)의 주요 UI 상태를 재현한다 — 가능한 한 빈 상태 / 에러 상태 / 정상 상태를 각각 캡처.
   - 모바일 우선 제품이므로 `mcp__playwright__browser_resize`로 모바일 폭(예: 390x844)에서도 1회 확인.
4. `mcp__playwright__browser_console_messages`로 콘솔 에러/경고를 수집한다.
5. `mcp__playwright__browser_take_screenshot`으로 각 상태 스크린샷을 저장한다.

보고 형식(이것만 메인으로 반환):
- 렌더된 핵심 요소 ✅/❌
- 확인한 UI 상태 목록과 결과
- 콘솔 에러/경고 유무
- 스펙 대비 불일치 항목
- 스크린샷 경로

실제 사용자 입력 텍스트를 스크린샷/로그에 남기지 말 것 (PII, SECURITY.md). 테스트용 더미 텍스트만 사용.

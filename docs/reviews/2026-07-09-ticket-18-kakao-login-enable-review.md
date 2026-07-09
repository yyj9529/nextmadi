# Review: Kakao 로그인 진입점 활성화 (#18 남은 작업)

- 날짜: 2026-07-09
- 구현: Opus 4.8 (카드 배정 Sonnet 4.6 → owner 승인하에 Opus 진행, 변경 규모 버튼 1개)
- 리뷰: spec-reviewer (구현과 다른 시각의 독립 스펙 리뷰어). 카드 배정 리뷰어는 Codex —
  필요 시 owner가 Codex 교차 리뷰 추가 가능.
- 대상 diff: `src/app/(app)/login/LoginExperience.tsx` (Kakao 버튼 `disabled` 제거 +
  `onClick={() => void signInWithOAuthProvider("kakao")}` + 문구 "준비 중" 제거)
- 계획: `docs/exec-plans/2026-07-09-ticket-18-kakao-login-enable.md`

## 게이트 1 — 스펙 준수 (`/spec-check s03`)

| 항목 | 판정 |
|------|------|
| AC1 3-provider 노출·순서 (Google→Kakao→email) | ✅ |
| AC2 provider 탭 → OAuth consent | ✅ (Kakao가 Google과 대칭 배선) |
| AC3 콜백 후 온보딩 상태별 라우팅 | ✅ (공통 경로, 미변경) |
| AC4 약관/개인정보 링크 | ✅ (미변경) |
| Edge: same-email 다른 provider account-linking 안내 | ✅ (provider 무관 공통 경로) |

#18 스코프 기준 위반 없음. 이메일 "가입 완료" disabled·상단 카피 상이는 각각 #19·기존
상태로 이번 변경 밖.

## 게이트 2 — 독립 리뷰 (spec-reviewer)

최종 판정: **PASS-with-questions, blocker 없음.** Kakao가 signIn 콜백/provisioning/jwt/
session/에러 처리 어디에도 누락 분기 없음. BFF(ADR-010) 경로 준수. `auth.test.ts`·
`oauth-*.test.ts`가 kakao 케이스 이미 커버.

### 머지 전 owner 확인 사항 (코드 결함 아님 — 외부 설정/런타임)

1. **Kakao email 선택 동의 런타임 리스크.** email 미반환 시 코드는 안전하게
   `callback_error=oauth` 일반 메시지로 떨어짐(잘못된 데이터 저장 없음). 단 S03 스펙에
   "Kakao email 미제공" 전용 안내가 없어 정상 사용자가 원인 불명 에러를 볼 수 있음 →
   전용 안내 필요 여부 owner 판단. **(owner: 2026-07-09 콘솔 email 동의항목 검증 완료 확인)**
2. **콘솔 설정은 코드로 검증 불가.** redirect URI 등록·email 동의항목 승인·
   `AUTH_KAKAO_ID/SECRET`는 코드 밖. **(owner: 검증 완료 확인)**
3. **실제 Kakao 계정 스모크 테스트 권장.** NextAuth Kakao provider가 기본 scope로 email을
   요청/매핑하는지 provider 버전 의존 → 실계정 1회 로그인으로 확인 권장. **(미수행 — 라이브
   provider 호출은 owner 승인 영역)**

## UI 게이트 (브라우저 증빙)

`/ui-verify` 실행했으나 **Playwright MCP 브라우저 도구가 이 환경에 미연결**되어 증빙 생성 실패.
로컬 dev 서버는 `http://localhost:3000/` HTTP 200 정상 기동 확인 — 중단 원인은 도구 부재.
Playwright MCP 연결 후 `/ui-verify` 재실행 시 `/login` 스냅샷·상태별 스크린샷·콘솔 로그 생성 가능.

## 자동 검증

- `bun test src/lib/auth/oauth-client.test.ts` — 5 pass (kakao signIn 배선 회귀 없음)
- `bunx tsc --noEmit` — 통과

# Exec plan: Kakao login entry point 활성화 (#18 남은 작업)

Status: **In progress 2026-07-09.** Owner가 Kakao 콘솔 검증(redirect URI + email 동의항목)
완료를 확인했고 활성화 진행에 동의함. 구현 모델은 카드 배정(Sonnet 4.6) 대신 Opus 4.8로
진행(owner 승인, 변경 규모가 버튼 1개 수준).

## Goal

이슈 #18은 2026-07-06 감사에서 PARTIAL로 재분류됨. Google + Kakao provider는 NextAuth에
이미 등록돼 있고 백엔드도 양쪽을 수락함. 남은 것은 **S03 로그인 화면의 Kakao 버튼이
하드-비활성화("준비 중")** 상태라는 것 하나. 이 버튼을 실제 `signIn("kakao")` 흐름에
연결해 S03 AC1(Google → Kakao → email 순서, 3개 provider 노출)을 충족한다.

## 왜 지금까지 막혀 있었나

이전 exec-plan(`2026-06-20-nextauth-google-kakao-oauth.md`) 마지막 줄:

> Kakao remains provider-capable in the shared OAuth provisioning code, but the S03
> Kakao entry point is disabled until the Kakao developer-console email consent and
> callback configuration can be verified end to end.

즉 코드가 아니라 **Kakao 콘솔의 email scope 동의항목 + redirect URI 검수**가 게이트였다.
Kakao가 email scope를 반환하지 않으면 `user.email`이 빈 문자열이 되어 `provisionOAuthIdentity`가
빈 이메일로 백엔드를 호출하고 실패한다. Owner가 2026-07-09에 콘솔 검증 완료를 확인.

## Source specs

- `docs/screens/s03.md` — AC1(3-provider 노출/순서), AC2(provider 탭 시 OAuth consent),
  Edge: same-email 다른 provider 시 account-linking 안내.
- `docs/auth.md` + ADR-010 — BFF 핸드오프. Kakao 콜백도 Google과 동일 경로
  (signIn 콜백 → `provisionOAuthIdentity` → `X-Internal-Auth` → 백엔드).
- 이전 exec-plan `2026-06-20-nextauth-google-kakao-oauth.md`.

## 변경 범위 (in scope)

- `src/app/(app)/login/LoginExperience.tsx`의 Kakao 버튼:
  - `disabled` 제거, 문구 "카카오로 계속하기 준비 중" → "카카오로 계속하기"
  - `onClick={() => void signInWithOAuthProvider("kakao")}` 배선
  - 상단 주석의 "Kakao entry point is deferred" 갱신
- 회귀 방지: `signInWithOAuthProvider("kakao")`는 `oauth-client.test.ts`에서 이미 커버됨.

## Out of scope

- 백엔드/provisioning 코드: 이미 Kakao 수락 (변경 없음).
- Same-email 충돌 매트릭스 전체(#21). #18은 기존 `account_link_required` 안내만 유지.
- 이메일 매직링크(#19).
- Kakao 콘솔 설정 자체(owner 소관, 검증 완료 확인함).

## 검증

1. `bun test src/lib/auth/oauth-client.test.ts` — Kakao signIn 배선 회귀 없음.
2. `/spec-check s03` — S03 AC1(3-provider 노출·순서) 대조.
3. `/ui-verify`(Playwright) — 로그인 화면 렌더: Kakao 버튼이 활성 상태로 노출되고 콘솔
   에러 없음. (라이브 Kakao consent까지 클릭하지는 않음 — provider 호출은 owner 승인 영역.)
4. Codex(또는 spec-reviewer, 구현과 다른 시각) 독립 리뷰 → `docs/reviews/`.

## Risk

낮음. 배선 자체는 Google과 대칭이고 provider 코드는 이미 존재. 유일한 실질 위험은 Kakao가
email scope를 안 줄 때인데, 이는 owner 콘솔 검증으로 해소됐다고 확인함. 만약 라이브에서
email 미반환이 재발하면 signIn 콜백이 빈 이메일로 실패 → S03 `callback_error` 상태로
안전하게 떨어진다(사용자에게 재시도 안내). 데이터 오염 없음.

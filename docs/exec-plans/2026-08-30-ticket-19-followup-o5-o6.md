# #19 후속 — O5(dev 콘솔 폴백)와 O6(링크 만료 집행)

`docs/reviews/2026-08-28-ticket-19-email-magic-link-review.md`가 머지 시점에 열어둔 세 건
중 둘을 닫는다. 남은 O2는 정책 결정이 선행이라 이슈로 분리했다 (#152).

## 범위

| | 리뷰 지적 | 조치 |
|---|---|---|
| O5 | SMTP 미설정 시 매직링크를 콘솔에 출력하는데 가드가 `NODE_ENV`뿐 | 명시적 opt-in으로 교체 |
| O6 | 링크 만료를 캐럿 범위 프리릴리스 안의 한 줄만 판정 | BFF 어댑터에서도 판정 + 버전 고정 |

O1/O3/O4는 `fdd40f7`에서 이미 닫혔다. 리뷰의 "coverage gaps"(동시성, soft-deleted users,
wire-format pinning)는 이 계획 밖이다 — 대부분 #18에서 물려받은 것이라 별도로 다뤄야 한다.

## O5 — 콘솔 폴백을 명시적 opt-in으로

**문제.** `NODE_ENV !== "production"`은 "이 프로세스가 배포돼 있는가"를 답하지 않는다.
스테이징 박스나 변수를 넘기지 않고 띄운 컨테이너는 조건을 만족하면서 외부에 노출돼 있고,
출력되는 링크는 그 자체로 로그인 자격증명이다. 기본값이 여는 쪽인 게 문제였다.

**조치.** `AUTH_EMAIL_DEV_CONSOLE=true`일 때만 열고, 그때도 `VERCEL`/`VERCEL_ENV`가 있거나
`NODE_ENV`가 production이면 던진다. 스위치가 하나, 배포 감지가 둘 — 뒤의 둘은 스위치가 실수로
배포 환경 변수에 들어갔을 때를 위한 것이다.

로컬 `.env`에 플래그를 넣어야 dev 흐름이 유지된다. `.env`는 gitignore 대상이라 워크트리마다
따로 넣는다 (nextmadi/madi-1/madi-2 세 곳 반영 완료).

## O6 — 만료 판정을 우리 코드에도 둔다

**문제 1.** 판정이 `@auth/core/lib/actions/callback/index.js:147` 한 줄뿐인데
`package.json`이 캐럿 범위 프리릴리스를 가리켰다.

**문제 2 (조사 중 발견).** 그 비교는 `expires`가 파싱되는 값일 때만 동작한다. 파싱되지 않는
값이 오면 `new Date(...)`가 `Invalid Date`가 되고 `NaN < Date.now()`는 **false** —
만료된 링크가 영구 유효해진다. 즉 의존성 문제와 무관하게 우리 쪽 드리프트만으로도 열린다.

계약이 요구하는 ISO 문자열인지까지 보는 것은 아니다. epoch 밀리초 숫자나 RFC 날짜 문자열은
파싱되고, 파싱된 순간이 옳으면 판정도 옳다. 계약 고정은 #19 리뷰의 별도 항목이다.

**조치.** `bff-adapter.ts`의 `useVerificationToken`이 만료됐거나 유한하지 않은 `expires`를
`null`로 거절한다. 유한성을 먼저 보는 이유가 문제 2다 — 이 방향의 실패는 닫히는 쪽이어야 한다.
`package.json`은 `5.0.0-beta.31`로 고정 (`--frozen-lockfile` 통과, lockfile 무변화).

**안전성.** 백엔드 `consume`은 만료 여부와 무관하게 행을 먼저 삭제하므로 단일 사용은 그대로다.
Auth.js에서 null과 만료된 토큰은 모두 `Verification`으로 끝나 사용자가 보는 화면이 같다
(`callback/index.js:153`).

## 검증

- 새 테스트 3건이 옛 동작에 대해 실제로 실패하는 것을 확인한 뒤 되돌렸다. 통과만으로는
  회귀 가드를 증명하지 못한다
- 프론트: 294 pass / 0 fail, typecheck·lint·build clean
- 백엔드: 431 tests, 430 passed, 0 failed, 1 skipped (기존 `@Disabled` OpenAI 라이브 테스트).
  `cleanTest`로 Gradle UP-TO-DATE 스킵을 막고 돌린 수치다
- 브라우저 확인은 하지 않았다. UI 변경이 없고 두 조치 모두 서버 측 판정이다

## 남는 것

- O2 → #152 (주소 정규화 정책, per-IP 리미터 위치, 임계값 — 결정 후 착수)
- #19 AC1(실제 SES 왕복)은 여전히 오너 작업

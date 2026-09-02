# #24 계정 삭제 14일 유예 — DELETE /me + cancel-deletion

- 티켓: #24 (E03.7)
- 스펙: `docs/screens/s11.md` User Story 3, `docs/api/openapi.yaml` `/me` delete,
  `/me/cancel-deletion` post
- 브랜치: `feat/24-account-deletion` (base = `feat/56-s11-settings`, PR #155)

## 배경

이슈 본문의 감사 코멘트가 남긴 REMAINING은 세 가지였다: `DELETE /me` 없음,
`POST /me/cancel-deletion` 없음, 하드 삭제 스윕 없음. 스윕은 이슈 본문이 E02.3 소관이라고
명시하고 있어 이번 범위에서 뺐다 (오너 확인).

계약은 이미 `openapi.yaml`에 있었다. 이번 작업은 계약을 새로 정하는 게 아니라 구현이
계약을 따라잡는 것이다. 그래서 openapi 변경분이 없다.

## base를 main이 아니라 #155로 잡은 이유

S11 설정 화면은 PR #155가 목 데이터에서 실 API로 갈아엎는 중이고, 그 PR이 삭제 경로를
"#24 대기"로 명시해 두고 비워놨다 (`SettingsExperience.tsx`의 주석과 "계정 삭제는 아직
준비 중이에요" 토스트). main 위에 쌓으면 곧 교체될 목 코드에 삭제 배선을 붙이는
셈이라 `docs/solutions/mock-to-real-drift.md` 그대로다. 스택 PR로 간다.

## 구현

### 백엔드

- `UserRepository`에 `scheduleDeletion(userId, graceDays)` / `cancelDeletion(userId)` 추가.
  둘 다 활성 행(`deleted_at IS NULL`)에만 걸리고, 대상이 없으면 false.
- `JdbcUserRepository`는 `now() + make_interval(days => ?)`로 유예를 세운다. 유예 일수를
  INTERVAL 리터럴에 문자열로 붙이지 않기 위해서다 — 바인드 파라미터로 남긴다.
- `UserService.scheduleDeletion` / `cancelDeletion`: principal 검증(익명 세션 토큰 401,
  UUID 아닌 클레임 400)은 기존 `requireAuthenticatedUser`를 그대로 쓰고, 대상 행이 없으면 404.
  유예 14일은 `DELETION_GRACE_DAYS` 상수.
- `MeController`에 `@DeleteMapping` / `@PostMapping("/cancel-deletion")`, 둘 다 204.
  `InternalAuthFilter`가 `/api/v1/**` 페일클로즈라 새 경로도 자동으로 보호된다.

재예약은 실패가 아니라 창을 다시 여는 것으로 뒀다. 두 번째 확인이 유예를 줄이는 쪽이
사용자에게 더 나쁘다.

### 프론트엔드

- `src/lib/user/account-deletion.ts` — DELETE /api/v1/me, POST /api/v1/me/cancel-deletion.
  둘 다 204라 성공은 "던지지 않음"으로만 표현된다.
- `src/app/api/me/route.ts`에 `DELETE`, `src/app/api/me/cancel-deletion/route.ts`에 `POST`.
  기존 라우트들과 같은 same-origin + 세션 검사.
- `SettingsExperience`의 "계속 진행"이 실제로 호출한다. **서버가 204로 답한 뒤에만**
  로그아웃한다. 먼저 로그아웃시키면 백엔드가 죽어 있어도 삭제된 것처럼 보인다.
- 안내 문구는 `/?account_deleted=1`로 넘긴다. 로그아웃은 전체 네비게이션이라 컴포넌트
  state가 도착지까지 못 간다. 랜딩(S01)이 파라미터 존재만 보고 렌더하고, 새로고침하면 사라진다.

### 재로그인 복원 (AC3)

이건 이미 돌고 있었다. `OAuthIdentityService` / `EmailIdentityService`가 로그인
프로비저닝 중에 `scheduled_deletion_at`을 그 자리에서 지운다. 그래서
`POST /me/cancel-deletion`은 재로그인 경로가 아니라, 세션을 이미 가진 클라이언트가
명시적으로 취소하는 경로다.

"다시 만나서 반가워요" 토스트는 넣지 않았다. 백엔드가 돌려주는
`canceledScheduledDeletion` 플래그가 BFF 프로비저닝 결과까지는 오지만 JWT/세션에는
실리지 않는다. 세션 모양을 바꾸고 "한 번만 보여주기"를 풀어야 해서, 복원 동작 자체와
분리해 별도 티켓으로 두는 편이 맞다.

## 게이트 결과

| 게이트 | 결과 |
|---|---|
| `gradlew spotlessCheck test` | BUILD SUCCESSFUL |
| `bun run typecheck` | exit 0 |
| `bun run lint` | exit 0 |
| `bun test` | 328 pass / 0 fail |

**초록불이 덮고 있는 것**: `JdbcUserRepositoryTests` 10건이 전부 skipped다. 로컬에
Docker가 안 떠 있어서 Testcontainers가 통째로 건너뛴다
(`docs/solutions/testcontainers-skipped-locally.md`). 즉 이번에 추가한
`make_interval` SQL과 `deleted_at IS NULL` 조건은 **아직 한 번도 실행되지 않았다**.
CI가 Docker와 함께 도는 것이 이 PR의 실질적 첫 검증이다.

## 브라우저 검증 (오너 실행, 2026-09-01)

| 확인 | 결과 |
|---|---|
| 설정 → 계정 삭제 → 계속 진행 → 랜딩에 "14일 안에 다시 로그인하면 복원돼요" | 통과 |
| 백엔드 끈 상태에서 삭제 시도 → 로그아웃되지 않고 에러 토스트만 | 통과 |
| 재로그인 후 `scheduled_deletion_at`이 NULL (psql로 0행 확인) | 통과 |
| `POST /api/me/cancel-deletion` → 204 (예약 없는 상태의 no-op 포함) | 통과 |

두 번째 줄이 이 티켓의 핵심이다. 정상 경로만 보면 삭제 요청과 로그아웃의 순서가 뒤집혀
있어도 똑같이 잘 되는 것처럼 보인다.

네 번째는 따로 날려야 했다. 화면에서 이 엔드포인트를 부르는 곳이 아직 없고(설정 화면의
"삭제 예약됨 — 취소" 배너는 이 티켓 범위 밖), 재로그인 복원은 이 경로를 타지 않기
때문에 수동 호출 없이는 브라우저에서 한 번도 실행되지 않은 채 머지됐을 것이다.

## 검증 중 발견한 별개 버그

구글/카카오 로그인이 콜백에서 500(`MissingAdapter`). `@auth/core`가 `hasEmail`을 모듈
전역에 래치해서, #19의 "OAuth 콜백에서만 어댑터를 뗀다" 전략이 성립하지 않는다.
`src/auth.ts`는 main과 동일하므로 이 티켓과 무관하고, #19 이후 전 브랜치에 있다.
→ #157로 분리. 위 재로그인 검증은 이메일 매직링크로 우회해서 진행했다.

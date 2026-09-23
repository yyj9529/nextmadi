# #175 커밋된 개발용 내부 인증 비밀로는 백엔드가 뜨지 않게 한다

- 티켓: #175
- 브랜치: `fix/175-refuse-dev-internal-auth-secret` (base = `origin/main` 3842b7b)
- 위험 분류: auth — CLAUDE.md 9절 2-에이전트 리뷰 적용

## Goal

`phraselog.internal-auth.secrets`에 저장소에 커밋된 개발용 값
(`dev-placeholder-internal-auth-secret-do-not-use-in-prod`)이 들어 있으면, 활성 프로필이
`local` 또는 `test`가 아닌 한 백엔드 기동을 실패시킨다. 프로필 설정이 빠진 배포가 공개된
비밀로 조용히 도는 대신 부팅 시점에 크게 실패하게 하려는 것이다.

## Source specs

- ADR-010 (BFF 내부 인증, `X-Internal-Auth` HS256)
- `docs/architecture.md` — 내부 토큰 흐름(182행 부근), Secrets 보관(241–243행), 배포(266행)
- `backend/src/main/resources/application.yml` 36행 (개발용 기본값),
  `application-prod.yml` 29행 (`${INTERNAL_AUTH_SECRET}`, 기본값 없음)

## 현재 상태 (이슈 본문 정정 포함)

- default 프로필은 개발용 값으로 폴백한다. `prod`는 값이 없으면 기동 실패. 여기까지는 이슈와 같다.
- 이슈는 "prod 프로필을 켜는 곳이 저장소에 없다"고 했지만,
  `backend/deploy/systemd/phraselog-backend.service`가 `Environment=SPRING_PROFILES_ACTIVE=prod`를
  건다. 빠진 것은 `architecture.md`의 기록이다. 그래서 마지막 수락 기준은 새 결정이 아니라
  기존 사실을 문서에 옮기는 일이다.
- `@SpringBootTest` 6개 중 5개는 `phraselog.internal-auth.secrets`를 테스트 전용 값으로
  덮어쓴다. 개발용 기본값에 기대는 것은 `PhraselogBackendApplicationTests` 하나다.

## Files expected to change

- `backend/src/main/java/com/phraselog/auth/config/` — 기동 시 검사 (새 클래스 또는
  `InternalAuthConfiguration` 안)
- `backend/src/main/resources/application.yml` — 개발용 값이 검사와 같은 출처를 쓰도록
  할지는 구현 중 판단
- `backend/build.gradle` — `test` 태스크가 `test` 프로필로 돌게
- `backend/src/test/java/com/phraselog/auth/config/` — 새 테스트
- `docs/architecture.md` — prod 프로필을 거는 위치 한 줄

## Acceptance criteria

1. 비밀 목록 어디에든(회전 오버랩의 두 번째 자리 포함) 개발용 값이 있고, 활성 프로필에
   `local`·`test`가 없으면 기동이 실패한다. 메시지는 원인과 해결(실제 비밀 주입 또는
   개발 프로필 활성화)을 말하고, 비밀 값 자체는 싣지 않는다. 비밀은 공개된 값이지만 기존
   `InternalAuthVerifier`의 "비밀 값은 메시지에 담지 않는다" 원칙을 따른다.
2. `local` 또는 `test` 프로필이 켜져 있으면 개발용 값으로 기동된다.
3. 개발용 값이 아닌 비밀이면 프로필과 무관하게 기존 동작 그대로다.
4. `./gradlew spotlessCheck test build`가 통과하고, 테스트 집계에서 실행된 개수를 확인한다.
5. `architecture.md`에 prod 프로필이 systemd 유닛에서 설정된다는 사실이 기록된다.

## Test plan

TDD. 검사 코드보다 테스트를 먼저 쓰고 빨간불을 확인한다.

- 거부 경로: 개발용 값 + 프로필 없음 → 컨텍스트 기동 실패, 메시지 확인
- 거부 경로: 개발용 값이 목록 두 번째 자리 + 프로필 없음 → 실패
- 거부 경로: 개발용 값 + `prod` 등 비개발 프로필 → 실패
- 허용 경로: 개발용 값 + `local` → 기동 / 개발용 값 + `test` → 기동
- 무관 경로: 다른 비밀 + 프로필 없음 → 기동
- 전체 컨텍스트를 매번 띄우지 않도록 `ApplicationContextRunner` 계열을 우선 검토한다.
- 검사 코드를 일시적으로 빼서 거부 테스트가 빨개지는지 확인한다
  (`silent-failure-looks-like-success` 노트).
- `cleanTest`로 강제 재실행하고 Docker 상태와 skipped 수를 dev-log에 적는다
  (`testcontainers-skipped-locally` 노트).

## Risk areas

- **배포 기동 실패:** prod는 이미 기본값 없이 Secrets Manager 값을 쓰므로 검사에 걸리지
  않는다. Secrets Manager는 아직 만들지 않았다 (오너 확인, 2026-09-23). 만들 때 개발용 값을
  넣으면 기동이 실패하는데, 그게 의도한 동작이다.
- **로컬 개발:** README 절차(`--spring.profiles.active=local`)는 영향 없음. 프로필 없이
  `bootRun`하던 사람은 실패한다. 의도한 동작이며 에러 메시지가 해결법을 알려준다.
- **IDE에서 개별 테스트 실행:** Gradle을 거치지 않는 러너는 `test` 프로필이 안 걸릴 수 있다.
  기본값에 기대는 테스트가 하나뿐이라 영향은 작다. 구현 중 확인한다.
- `@ConditionalOnBean` 사용 금지 (`spring-conditional-bean-ordering`).

## Decision log

- **허용 목록 방식 (오너 결정, 2026-09-23).** 개발용 값은 `local`/`test` 프로필에서만 허용한다.
  "`prod`일 때만 거부"는 prod에 이미 기본값이 없어 아무것도 막지 못하고, 실제 위험인
  "프로필 누락"을 통과시키므로 기각.
- **테스트는 `test` 프로필로 실행.** 기본값에 기대는 테스트가 하나뿐이라 그 테스트에만 비밀을
  넣는 방법도 있다. 하지만 그러면 앞으로 추가되는 `@SpringBootTest`도 매번 비밀을 넣어야 한다.
  테스트 실행 전체에 프로필을 거는 쪽이 재발을 막는다. 거는 방법은 구현 중 정한다.
- **리뷰어:** 구현은 Claude Code. 리뷰는 오너가 Codex 또는 `spec-reviewer` 중 지정한다.

## Final outcome

- 수락 기준 1–3, 5 충족. 4는 로컬 기준 충족(아래), Testcontainers 스위트는 CI에서 확인한다.
- TDD 순서: 상수만 추가한 상태에서 거부 테스트 3건 빨간불 → 검사 추가 후 7/7 초록.
  `PhraselogBackendApplicationTests`는 test 프로필을 걸기 전 3건 실패, 건 뒤 통과.
- 리뷰 지적 반영 후 검사 호출을 일시로 빼서 거부 3건이 다시 빨개지는 것을 확인하고 복구.
- 로컬 `./gradlew cleanTest spotlessCheck test build`: 462 tests, 0 failed, 117 skipped.
  스킵은 Docker 미가동으로 인한 Testcontainers 스위트와 live 테스트다. **로컬 미검증, CI 확인 필요.**
- 리뷰: `docs/reviews/2026-09-23-175-refuse-dev-internal-auth-secret-review.md` (pass with notes).

## What changed after execution

- 계획 단계에서 "테스트 6개가 깨진다"고 오너에게 설명했지만 실제로 기본값에 기대는 것은
  `PhraselogBackendApplicationTests` 하나였다. 나머지 5개는 자기 비밀을 넣는다. 코드를 다
  읽기 전에 grep 결과 개수만 보고 영향 범위를 말한 것이 원인이다. 결정(테스트 전체에
  프로필)은 그대로 유지했다.
- 검사 테스트는 계획대로 `ApplicationContextRunner`를 썼다. 다만 Gradle이 건 시스템 프로퍼티가
  러너의 기본 환경에 새어 들어와 거부 테스트를 무의미하게 만들 수 있어서, 시스템 프로퍼티를 읽지
  않는 `MockEnvironment`로 컨텍스트를 만들었다.
- 거부 테스트가 `hasFailed()`만 보던 것을 리뷰가 잡았다. 첫 번째 거부 테스트만 원인을 확인하고
  나머지 둘은 빠뜨렸다. `silent-failure-looks-like-success`의 "아무것도 검사하지 않는 단언"
  계열이지만, 빨간불 확인 단계에서는 드러나지 않았다(검사가 없으면 어차피 기동이 성공하므로).
  빨간불 확인은 "이 검사가 없으면 실패하는가"만 증명하고 "다른 이유로 실패해도 잡아내는가"는
  증명하지 않는다.

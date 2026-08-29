# Testcontainers 스킵 → 안 돈 테스트가 통과로 보임

## 증상

`BUILD SUCCESSFUL`이 떴는데 실제로는 통합 테스트가 한 건도 돌지 않았다.

- 로컬 통합 테스트 9개가 Docker 부재로 조용히 스킵. 빌드는 그대로 초록
  (`20260623_0614`)
- CI Backend 잡 초록불. 그런데 로그에 `docker` / `testcontainers` / `postgres`
  검색 결과 0건 — 컨테이너 테스트가 실제로 돌았는지 증명할 수 없었다 (`20260802_0830`)
- `./gradlew test build`가 `Task :build UP-TO-DATE`로 20초 만에 `BUILD SUCCESSFUL`.
  직전 실행(113 skipped)의 결과를 그대로 재사용한 것이라 테스트가 한 건도 돌지 않았다
  (`20260828`)

## 근본 원인

`disabledWithoutDocker=true`는 Docker가 없는 환경에서 테스트를 **실패가 아니라 스킵**으로
처리한다. 스킵은 빌드 결과에 영향을 주지 않으므로 `BUILD SUCCESSFUL`이 그대로 뜬다.
집계 출력이 없으면 "무엇이 통과했는가"를 알 수 없고, 조건부 skip이 있는 스위트에서는
초록불이 "검증됨"을 뜻하지 않는다.

Gradle의 up-to-date 재사용이 이걸 한 겹 더 가린다. 입력이 안 바뀌면 이전 실행 결과를
재사용하는데, 그 이전 실행이 "113 skipped"였어도 똑같이 재사용한다.

## 올바른 방법

- **테스트 개수를 눈으로 확인한다.** `BUILD SUCCESSFUL`이 아니라
  `N tests, N passed, 0 skipped`를 본다. 개수가 안 나오면 실행되지 않은 것이다
- **재검증은 `cleanTest`로 강제한다.** up-to-date 재사용은 나쁜 결과도 똑같이 재사용한다
- 스킵된 테스트는 스킵으로 보여야 한다. `build.gradle`의
  `testLogging { events 'skipped','failed' }` + `afterSuite` 집계가 이 역할을 한다
  (커밋 `5a30fda`, 2026-08-02 도입)
- **Docker가 안 떠 있으면 스킵을 통과로 적지 않는다.** dev-log와 리뷰에 "로컬 미검증,
  CI에서 확인 필요"로 명시한다. 실제로 그렇게 적어온 세션들이 있고, 그게 옳다
  (`20260614_0317`, `20260623_0614`, `20260715_0510`)

## 자동화 후보

재발 7회. 2026-08-02의 testLogging 집계는 **불충분하다** — 집계는 사람이 읽어야
작동하고, 백엔드에만 적용돼 있으며, up-to-date 재사용은 막지 못한다.
에스컬레이션 규칙상 4회 이상이므로 조치 자체를 재설계해야 한다.

- 후보: CI에서 Docker 필요 테스트의 실행 건수가 0이면 잡을 실패 처리 (사람이 로그를
  읽는 데 의존하지 않는 유일한 안)
- 후보: CI의 백엔드 게이트를 `cleanTest test`로 고정해 up-to-date 재사용을 배제

## 재발 이력

- `20260614_0317_flyway-error-contract`
- `20260619_2255_ticket41-expression-save-backend`
- `20260623_0614_s08-expression-list-detail-delete-api`
- `20260627_1229_tts-playback-endpoint`
- `20260715_0510_ticket-15-seed-landing-examples`
- `20260802_0830` (CI 로그에 컨테이너 흔적 0건)
- `20260828_ticket-19-u6-u7-review` (`Task :build UP-TO-DATE`로 0건 실행)

## Related

- [silent-failure-looks-like-success](silent-failure-looks-like-success.md) — 같은
  "초록불이 아무것도 증명하지 않는다" 계열이지만 원인이 다르다. 이쪽은 환경 때문에
  안 돈 것이고, 저쪽은 돌았는데 실패를 삼킨 것이다.

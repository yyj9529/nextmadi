# Solutions — 실수 대장

여기는 "실수를 자산으로 바꾸는" 단일 저장소다. PhraseLog에서 실제로 발생한 실수만
기록한다. 가정이나 일반론은 넣지 않는다.

기존에 `docs/harness.md` section 8이 "매 사이클마다 durable learning note 1개"를
남긴다고 선언했지만 24개 티켓이 지나도록 이 디렉터리는 비어 있었다. 이 문서가 그
공백을 메우고, 실수 기록의 유일한 저장소 역할을 한다. 별도 `mistakes.md`는 만들지
않는다 — 저장소가 둘이면 둘 다 비게 된다.

## 파이프라인

1. **발생** — 리뷰(`docs/reviews/`), 게이트(`docs/quality-gates.md`), 실행 중 에러
2. **수집** — dev-log(`%USERPROFILE%\Desktop\dev-logs\`)에 서술로 남긴 뒤, 아래 대장에
   패턴 한 줄 추가 (같은 패턴이면 재발 횟수 +1)
3. **반영** — 아래 에스컬레이션 규칙에 따라 규칙/테스트/훅/프롬프트로 승격
4. **누적** — 패턴별 노트가 이 디렉터리에 쌓인다

## 에스컬레이션 규칙

재발 횟수가 기준이다. 심각도가 아니라 **횟수**로 판단한다 — 심각도는 매번 다르게
평가되지만 횟수는 다투지 않는다.

| 재발 | 조치 |
|---|---|
| 1회 | dev-log에 기록만. 노트 없음 |
| 2회 | 이 디렉터리에 노트 작성 + 대장에 등재 |
| 3회 | 자동으로 막는다 — hook, 테스트, lint 규칙, CI 게이트 중 하나 |
| 4회 이상 | 3회 조치가 실패했다는 뜻. 조치 자체를 재설계한다 |

"주의하겠다"는 조치가 아니다. 사람의 기억에 의존하는 대책은 3회 칸에 쓸 수 없다.

## 실수 대장

재발 횟수는 dev-log 101건의 에러 섹션을 소급 분석해 산출했다 (2026-08-25 1차 집계).
세션 단위 집계이므로 한 세션에서 두 번 겪은 것은 1로 센다 — 실제 발생 횟수의 하한선이다.

| 패턴 | 유형 | 재발 | 현재 반영 | 노트 |
|---|---|---:|---|---|
| spotless 포맷 위반으로 CI 실패 | 프로세스 | 13 | 메모리만 (강제 없음) | [spotless-before-push](spotless-before-push.md) |
| 생성 파일 churn을 커밋에 섞음 | 프로세스 | 6 | 없음 | [generated-file-churn](generated-file-churn.md) |
| Windows 인코딩 (mojibake/BOM/cp949) | 환경 | 9 | 없음 | [windows-encoding](windows-encoding.md) |
| git 브랜치 위생 사고 | 프로세스 | 8 | 메모리 2건 | [branch-hygiene](branch-hygiene.md) |
| Testcontainers 로컬 스킵 → 미검증 통과 | 검증 | 7 | testLogging 집계 (2026-08-02) — **불충분, 재설계 필요** | [green-build-proves-nothing](green-build-proves-nothing.md) |
| 조용한 실패가 성공처럼 보임 | 설계 | 7 | 부분 | [green-build-proves-nothing](green-build-proves-nothing.md) |
| 도구 문법 혼용 (Bash/PowerShell, npx) | 프로세스 | 6 | 메모리 1건 (재발함) | [tool-syntax-mixing](tool-syntax-mixing.md) |
| Spring `@ConditionalOnBean` 순서 함정 | 코드 | 4 | 없음 | [spring-conditional-bean-ordering](spring-conditional-bean-ordering.md) |
| stale `.next` 캐시 → 404/타입 오류 | 환경 | 4 | 메모리 1건 | [stale-next-cache](stale-next-cache.md) |
| effect 내 동기 setState (lint) | 코드 | 3 | 관행만 (문서 없음) | [set-state-in-effect](set-state-in-effect.md) |
| 목 데이터 ↔ 실데이터 계약 드리프트 | 코드 | 2 | 없음 | [mock-to-real-drift](mock-to-real-drift.md) |
| 테스트가 앰비언트 env를 읽어 로컬/CI가 갈림 | 검증 | 2 | 공유 `withoutEnv` 헬퍼 | [ambient-env-in-tests](ambient-env-in-tests.md) |
| 라이브러리 실제 호출 지점을 안 읽고 설계 확정 | 설계 | 2 | 없음 | [library-call-sites-unread](library-call-sites-unread.md) |
| 도는 dev 서버의 브랜치를 확인 안 하고 디버깅 | 환경 | 2 | 없음 | [dev-server-branch-unverified](dev-server-branch-unverified.md) |
| 실행 안 한 검증을 Pass로 기록 | 검증 | 1 | 없음 | — |
| 이슈 본문의 미검증 관찰이 repro를 오도 | 프로세스 | 1 | 없음 | 노트 없음 (1회) |
| 워크트리 전환 후 의존성 재설치 누락 | 환경 | 1 | 없음 | 노트 없음 (1회) |
| 샌드박스 권한 에스컬레이션 | 환경 마찰 | 17 | — | 자동화 대상 아님 (아래 참고) |

샌드박스 에스컬레이션은 실수가 아니라 설계된 마찰이다. `SECURITY.md`의 승인 경계가
의도대로 작동한 결과이므로 제거 대상이 아니다. 횟수만 기록하고 조치하지 않는다.

## 미이행 조치 (3회 이상인데 자동화 없음)

아래는 규칙상 이미 자동화되었어야 하는데 아직 사람 기억에 의존 중이다.
각 노트의 "자동화 후보" 항목에 구체안이 있다.

1. spotless (13회) — pre-push 또는 PreToolUse 훅
2. 생성 파일 churn (6회) — 스테이징 차단 훅
3. Windows 인코딩 (9회) — 커밋 전 mojibake/BOM 스캔
4. git 브랜치 위생 (8회) — `main` 편집/커밋 차단 훅
5. 도구 문법 혼용 (6회) — Bash 입력의 `@'` 패턴 차단
6. `@ConditionalOnBean` (4회) — 금지 어노테이션 테스트

## 노트 작성 형식

파일명 `kebab-case.md`. 아래 5개 항목만. 길게 쓰지 않는다 (한 화면 분량).

```
# <패턴 이름>
## 증상        — 겉으로 보이는 것
## 근본 원인    — 왜 그렇게 되는가 (추측 금지, 확인된 것만)
## 올바른 방법   — 지금부터 이렇게 한다
## 자동화 후보   — 3회 넘었으면 무엇으로 막을 것인가
## 재발 이력    — dev-log 파일명 목록
```

## Related

- `docs/harness.md` section 8 — 사이클 산출물
- `CLAUDE.md` 워크플로 4번 — 자기개선 루프
- `docs/quality-gates.md` — 완료 기준

# Solutions — 실수 대장

여기는 "실수를 자산으로 바꾸는" 단일 저장소다. PhraseLog에서 실제로 발생한 실수만
기록한다. 가정이나 일반론은 넣지 않는다.

기존에 `docs/harness.md` section 8이 "매 사이클마다 durable learning note 1개"를
남긴다고 선언했지만 24개 티켓이 지나도록 이 디렉터리는 비어 있었다. 이 문서가 그
공백을 메우고, 실수 기록의 유일한 저장소 역할을 한다. 별도 `mistakes.md`는 만들지
않는다 — 저장소가 둘이면 둘 다 비게 된다.

## 파이프라인

1. **발생** — 리뷰(`docs/reviews/`), 게이트(`docs/quality-gates.md`), 실행 중 에러
2. **수집** — dev-log(`%USERPROFILE%\Desktop\dev-logs\`)에 서술로 남긴 뒤,
   해당 노트의 `## 재발 이력`에 dev-log 파일명 한 줄 추가. 대장에 없는 새 패턴이면
   그때만 아래 표에 행을 추가한다 (아래 "재발 횟수는 어디에 있나" 참고)
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

## 재발 횟수는 어디에 있나

**이 표에는 숫자가 없다.** 재발 횟수의 유일한 출처는 각 노트의 `## 재발 이력`
목록이고, 횟수는 그 목록의 길이다. 이 표는 거기서 도출되는 **에스컬레이션 단계**만
들고 있다.

이렇게 나눈 이유가 있다. 예전에는 숫자가 이 표와 노트 양쪽에 있었고, 티켓마다 이 표의
같은 셀을 고치느라 PR이 연달아 충돌했다. 숫자는 충돌하면 한쪽을 고르게 되고 그 순간
재발 기록이 조용히 사라진다. 실제로 2026-08-29 시점에 세 패턴이 이미 어긋나 있었다
(`tool-syntax-mixing` 6 대 7건, `windows-encoding` 9 대 8건,
`green-build-proves-nothing` 7+7 대 10건).

지금 규칙은 이렇다.

- **재발 +1 = 해당 노트의 `## 재발 이력`에 dev-log 파일명 한 줄 추가.** 이 표는 안 건드린다
- 목록은 append라서 충돌해도 양쪽을 다 남기면 된다. 유실이 구조적으로 안 생긴다
- 이 표는 **새 패턴이 생기거나 에스컬레이션 임계(1/2/3/4)를 넘을 때만** 바뀐다
- 단계와 노트가 어긋나 보이면 **노트가 맞다**

초기 횟수는 dev-log 101건의 에러 섹션을 소급 분석해 산출했다 (2026-08-25 1차 집계).
세션 단위 집계이므로 한 세션에서 두 번 겪은 것은 1로 센다 — 실제 발생 횟수의 하한선이다.

## 실수 대장

`단계`는 재발 횟수가 요구하는 조치이고, `현재 반영`은 실제로 되어 있는 것이다.
**둘의 차이가 해야 할 일이다.**

| 패턴 | 유형 | 단계 | 현재 반영 | 노트 |
|---|---|---|---|---|
| spotless 포맷 위반으로 CI 실패 | 프로세스 | 재설계 | 메모리만 (강제 없음) | [spotless-before-push](spotless-before-push.md) |
| 생성 파일 churn을 커밋에 섞음 | 프로세스 | 재설계 | 없음 | [generated-file-churn](generated-file-churn.md) |
| Windows 인코딩 (mojibake/BOM/cp949) | 환경 | 재설계 | 없음 | [windows-encoding](windows-encoding.md) |
| git 브랜치 위생 사고 | 프로세스 | 재설계 | 메모리 2건 | [branch-hygiene](branch-hygiene.md) |
| Testcontainers 스킵 → 안 돈 테스트가 통과로 보임 | 검증 | 재설계 | testLogging 집계 (2026-08-02) — **불충분** | [testcontainers-skipped-locally](testcontainers-skipped-locally.md) |
| 조용한 실패가 성공처럼 보임 | 설계 | 재설계 | 부분 | [silent-failure-looks-like-success](silent-failure-looks-like-success.md) |
| 도구 문법 혼용 (Bash/PowerShell, npx) | 프로세스 | 재설계 | 메모리 1건 (재발함) | [tool-syntax-mixing](tool-syntax-mixing.md) |
| Spring `@ConditionalOnBean` 순서 함정 | 코드 | 재설계 | 없음 | [spring-conditional-bean-ordering](spring-conditional-bean-ordering.md) |
| stale `.next` 캐시 → 404/타입 오류 | 환경 | 재설계 | 메모리 1건 | [stale-next-cache](stale-next-cache.md) |
| effect 내 동기 setState (lint) | 코드 | 자동화 | 관행만 (문서 없음) | [set-state-in-effect](set-state-in-effect.md) |
| 목 데이터 ↔ 실데이터 계약 드리프트 | 코드 | 노트 | 없음 | [mock-to-real-drift](mock-to-real-drift.md) |
| 테스트가 앰비언트 env를 읽어 로컬/CI가 갈림 | 검증 | 노트 | 공유 `withoutEnv` 헬퍼 | [ambient-env-in-tests](ambient-env-in-tests.md) |
| 라이브러리 실제 호출 지점을 안 읽고 설계 확정 | 설계 | 노트 | 없음 | [library-call-sites-unread](library-call-sites-unread.md) |
| 도는 dev 서버의 브랜치를 확인 안 하고 디버깅 | 환경 | 노트 | 없음 | [dev-server-branch-unverified](dev-server-branch-unverified.md) |
| 실행 안 한 검증을 Pass로 기록 | 검증 | 기록 | 없음 | 노트 없음 (1회) |
| 실패할 수 없는 검사를 증거로 제시 | 검증 | 기록 | 없음 | 노트 없음 (1회) |
| 이슈 본문의 미검증 관찰이 repro를 오도 | 프로세스 | 기록 | 없음 | 노트 없음 (1회) |
| 워크트리 전환 후 의존성 재설치 누락 | 환경 | 기록 | 없음 | 노트 없음 (1회) |
| 샌드박스 권한 에스컬레이션 | 환경 마찰 | — | — | 자동화 대상 아님 (아래 참고) |

샌드박스 에스컬레이션은 실수가 아니라 설계된 마찰이다. `SECURITY.md`의 승인 경계가
의도대로 작동한 결과이므로 제거 대상이 아니다. 횟수만 기록하고 조치하지 않는다.
단계 칸이 비어 있는 이유다.

## 미이행 조치 (단계가 "자동화" 이상인데 자동화 없음)

아래는 규칙상 이미 자동화되었어야 하는데 아직 사람 기억에 의존 중이다.
여기에도 횟수를 적지 않는다 — 위 표와 같은 이유다. 각 노트의 "자동화 후보" 항목에
구체안이 있다.

1. spotless — pre-push 또는 PreToolUse 훅
2. 생성 파일 churn — 스테이징 차단 훅
3. Windows 인코딩 — 커밋 전 mojibake/BOM 스캔
4. git 브랜치 위생 — `main` 편집/커밋 차단 훅
5. 도구 문법 혼용 — Bash 입력의 `@'` 패턴 차단
6. `@ConditionalOnBean` — 금지 어노테이션 테스트
7. Testcontainers 스킵 — CI에서 Docker 필요 테스트 실행 건수가 0이면 잡 실패
8. 조용한 실패 — `await` 없는 `expect(...).rejects` lint 차단, eval fallback 제거
9. stale `.next` 캐시 — 아직 후보 없음 (노트 참고)
10. effect 내 동기 setState — lint 규칙 활성화

## 노트 작성 형식

파일명 `kebab-case.md`. 아래 5개 항목만. 길게 쓰지 않는다 (한 화면 분량).

```
# <패턴 이름>
## 증상        — 겉으로 보이는 것
## 근본 원인    — 왜 그렇게 되는가 (추측 금지, 확인된 것만)
## 올바른 방법   — 지금부터 이렇게 한다
## 자동화 후보   — 3회 넘었으면 무엇으로 막을 것인가
## 재발 이력    — 재발 1건당 한 줄. 이 목록이 횟수의 유일한 출처다
```

`재발 이력`은 **한 줄에 한 건**으로 쓴다. 쉼표로 이어 쓰면 한 줄에 두 건이 들어가
충돌 시 유실되고, 세는 것도 어려워진다.

```
## 재발 이력

- `20260614_0317_flyway-error-contract`
- `20260623_0614_s08-expression-list-detail-delete-api`
```

개별 dev-log에 귀속하지 못한 소급 집계분이 있으면 그것도 한 줄로 명시한다
("그 외 N건은 2026-08-25 소급 집계분"). 숫자를 조용히 맞추지 않는다.

## 이름이 바뀐 노트

`green-build-proves-nothing.md`는 원인이 다른 두 패턴을 한 파일에 담고 있어서
2026-08-29에 아래 둘로 나뉘었다. 그 이전 날짜의 dev-log, `docs/reviews/`,
`docs/exec-plans/`는 당시 기록이므로 옛 이름을 그대로 둔다.

- [testcontainers-skipped-locally](testcontainers-skipped-locally.md) — 환경 때문에
  테스트가 안 돌았다
- [silent-failure-looks-like-success](silent-failure-looks-like-success.md) — 돌았는데
  실패를 삼켰다

## Related

- `docs/harness.md` section 8 — 사이클 산출물
- `CLAUDE.md` 워크플로 4번 — 자기개선 루프
- `docs/quality-gates.md` — 완료 기준

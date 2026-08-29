# 테스트가 앰비언트 env를 읽어 로컬/CI 결과가 갈림

## 증상

"환경변수가 없을 때"를 검증하는 테스트가 로컬에서만 빨갛다. CI는 초록이다.
`get-landing-examples.test.ts`의 "returns [] when the backend base url is not
configured"가 로컬에서 `[]` 대신 실제 표현 3건을 받아 실패했다.

## 근본 원인

`bun test`는 저장소의 `.env`를 자동으로 로드한다. BFF 함수들은 인자가 없으면
`process.env.PHRASELOG_BACKEND_BASE_URL`로 폴백하도록 설계돼 있으므로 (20여 개
호출부가 동일), 테스트가 `backendBaseUrl: undefined`만 넘기면 미설정 경로가 아니라
**로컬 .env 값이 들어간 설정된 경로**를 탄다. 로컬 백엔드까지 떠 있으면 진짜 HTTP
요청이 나가 진짜 데이터가 돌아온다.

CI에는 `.env`가 없어 폴백이 비고, 그래서 통과한다. 로컬/CI 결과가 반대로 갈린다.

방향이 반대인 경우가 더 위험하다. 같은 테스트가 "설정됐을 때"를 보는 것이었다면
로컬 백엔드에 붙어 **초록으로** 나오면서 실제로는 아무것도 검증하지 않았을 것이다.
`green-build-proves-nothing`과 같은 계열이다.

## 올바른 방법

프로덕션 코드의 env 폴백은 의도된 설계다. 고칠 곳은 테스트다.

- 미설정 경로를 검증하려면 `src/lib/testing/without-env.ts`의 `withoutEnv()`로
  해당 변수를 명시적으로 지우고 그 안에서 호출한다
- 같은 블록에 `fetcher`를 반드시 주입한다. 주입하지 않으면 전역 `fetch`가 실제
  네트워크로 나간다
- 주입한 `fetcher`는 **비어 있지 않은 응답**을 돌려준다. 빈 응답을 주면 env가 다시
  새어 들어와 요청이 실제로 나가도 결과가 기대값과 같아 누출이 가려진다

주의: 결과값만 보는 어서션은 가드 회귀를 못 잡는다. `backendBaseUrl`이 undefined면
`new Request("undefined/api/...")`가 먼저 던지고 함수의 catch가 그걸 `[]`로 삼키므로,
가드를 지워도 테스트는 통과한다. 이 노트의 어서션이 잡는 것은 가드 회귀가 아니라
**env 누출**이다.

## 자동화 후보

재발 2회. 3회 미만이므로 노트와 공유 헬퍼까지만 둔다.

- 후보: `.test.ts`에서 `process.env`를 직접 읽는 것을 lint로 금지하고 `withoutEnv`
  경유만 허용
- 후보: 테스트 실행 시 `.env` 자동 로드를 끄고, 필요한 변수는 테스트가 직접 설정
  (bunfig의 테스트 preload로 `.env` 키를 비우는 방식)

## 재발 이력

`20260827_0754_ticket-19-email-magic-link-u1-u5` (email-provisioning — 작성 중
발견해 로컬 `withoutEnv` 헬퍼로 우회),
`20260829_0959_merge-4-open-prs` (#147 리뷰에서 landing 건 분리 판정)

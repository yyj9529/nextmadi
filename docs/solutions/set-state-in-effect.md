# effect 내 동기 setState

## 증상

`bun run lint`가 `react-hooks/set-state-in-effect`로 실패. effect 본문에서 곧바로
setState를 호출해 cascading render 경고가 난다.

발생 지점: `AnalysisModals`, `useLibrary`, `useReview`, `CountUp`.

## 근본 원인

effect가 마운트/의존성 변경 직후 동기적으로 상태를 바꾸면 React가 렌더를 연쇄로
다시 돈다. 데이터 로드 kickoff(`loadInitial()`)를 effect에서 바로 부르는 패턴이
반복적으로 이 규칙에 걸렸다.

세 번 모두 같은 해법으로 고쳤는데 **그 해법이 어디에도 문서화되지 않아** 매번
처음부터 다시 발견했다.

## 올바른 방법

저장소 관행은 마이크로태스크 지연이다.

```ts
useEffect(() => {
  let cancelled = false;
  queueMicrotask(() => {
    if (cancelled) return;
    loadInitial();
  });
  return () => { cancelled = true; };
}, [activeQuery]);
```

- kickoff는 `queueMicrotask`로 미루고 `cancelled` 플래그로 가드
- 언마운트 시 초기화가 목적이라면, effect로 리셋하지 말고 **open 동안에만 마운트되는
  내부 컴포넌트로 분리**한다 (`AnalysisLoadingModalBody` 패턴) — 언마운트가 곧 초기화
- 초기 state가 이미 원하는 값이면 setState 자체를 지운다 (`CountUp`의 `setDisplay(0)`)

## 자동화 후보

재발 3회. lint가 이미 잡아주므로 추가 자동화보다 **해법의 문서화**가 부족했던 건이다.
이 노트가 그 조치다. 4회차가 나오면 코드젠 템플릿이나 커스텀 훅(`useDeferredLoad`)으로
승격한다.

## 재발 이력

`20260612_0454`, `20260624_1530`, `20260625_1145` (+ `20260621_1248` ResultActions)

# 도는 dev 서버의 브랜치를 확인 안 하고 디버깅

## 증상

브라우저에서 재현되는 버그를 작업 폴더의 코드로 설명하려다 앞뒤가 안 맞는다.
코드에는 없는 동작이 화면에는 있고, 고쳐도 반영되지 않는다.

2026-08-29 세션: `localhost:3000/library`의 콘솔 에러를 조사하면서 `nextmadi`
(브랜치 `fix/login-broken`)의 코드를 읽었다. 실제로 :3000을 서비스하던 것은
`madi-1`(브랜치 `fix/143-voice-controller-revive`)이었다. 서버 응답 HTML의 스택
경로에 `C:\Users\ywj95\Desktop\madi-1\...`이 찍혀 있어서야 발견했다.

같은 세션에서 2차 오판도 있었다. 두 폴더를 "별도 클론"으로 보고 오너에게 그렇게
보고했는데, `git worktree list`를 돌려보니 **같은 저장소의 워크트리**였다.
`.git`을 공유하므로 브랜치 목록과 stash가 동일하다.

## 근본 원인

워크트리를 여러 개 쓰면 "작업 중인 폴더"와 "서버가 뜬 폴더"가 갈라진다.
Claude Code 세션의 cwd는 전자를 가리키는데, 브라우저는 후자를 본다. 둘을
이어주는 신호가 화면에 없다.

`git rev-parse --show-toplevel`은 워크트리마다 자기 경로를 돌려주기 때문에 이것만
보고 "다른 저장소"라고 결론 내리면 틀린다. 워크트리 판별은
`git rev-parse --git-common-dir` 또는 `git worktree list`로 해야 한다.

## 올바른 방법

브라우저에서 재현되는 문제를 조사할 때는 **코드를 읽기 전에** 서버의 출처부터 확인한다.

```bash
git worktree list
```

dev 서버가 어느 워크트리에서 떴는지 모르면 Next dev 응답의 스택 경로로 확인한다.

```bash
curl -s http://localhost:3000/<경로> | grep -o 'C:\\\\Users[^"]*' | head -1
```

확인한 폴더가 세션 cwd와 다르면, 코드를 읽기 전에 오너에게 알리고 어느 쪽에서
작업할지 정한다. 다른 폴더에서 고치면 화면에 반영되지 않는다.

폴더 2개가 같은 저장소인지 판단할 때는 `--show-toplevel`이 아니라
`--git-common-dir` / `git worktree list`를 쓴다.

## 자동화 후보

아직 2회라 자동 차단 대상은 아니다. 3회가 되면 SessionStart 훅에서
`git worktree list`를 돌려 워크트리가 2개 이상이면 경고 한 줄을 띄운다.
"주의하겠다"는 조치로 치지 않는다.

## 재발 이력

- 20260829_0658_library-roleplay-tab-error-and-branch-cleanup.md (2회차, 워크트리 오판 포함)
- 1회차는 2026-08-25 소급 집계분 (`docs/solutions/README.md` 1차 집계)

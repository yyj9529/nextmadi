# 도구 문법 혼용

## 증상

- 커밋 메시지 앞뒤에 `@` 문자가 박혔다. Bash 도구에서 PowerShell here-string
  (`@'...'@`)을 쓴 결과. `git commit --amend -F - <<'EOF'`로 재작성해야 했다
- `gh issue create --body @'...'@`가 본문 내 큰따옴표로 인자 경계가 깨져 실패
- `npx tsc`가 엉뚱한 패키지를 받아와 실행. `./node_modules/.bin/tsc`를 써야 했다
- slash command에서 `$1`이 비어 `gh issue view` 가 "accepts 1 arg(s), received 0"

## 근본 원인

이 환경에는 셸이 둘이다. Bash 도구는 POSIX sh, PowerShell 도구는 PowerShell 5.1.
다중행 문자열 문법이 서로 호환되지 않는데 겉보기에는 둘 다 "따옴표 블록"이라
헷갈린다.

주목할 점: **이미 메모리에 규칙이 있는데도 재발했다** (`20260804_0600`,
"기존 메모리에 있던 항목인데 또 틀렸다"). 기억에 의존하는 대책이 실패한 증거다.

## 올바른 방법

- Bash 도구 → heredoc `<<'EOF' ... EOF`
- PowerShell 도구 → here-string `@'...'@` (닫는 `'@`는 반드시 0열)
- `gh` 본문처럼 긴 텍스트는 인라인 대신 **파일로 쓰고 `--body-file`** 사용
- 로컬 바이너리는 `npx`가 아니라 `./node_modules/.bin/<name>`으로 직접 호출
- slash command 위치 인자는 0-based. 인자가 하나면 `$ARGUMENTS`를 쓴다

## 자동화 후보

재발 5회, 메모리 규칙이 이미 실패했으므로 훅이 필요하다.

- PreToolUse(Bash) 훅: 명령 문자열에 `@'` 또는 `'@`가 있으면 차단하고
  heredoc 예시를 출력
- 같은 훅에서 `npx tsc` / `npx eslint` 패턴 경고

## 재발 이력

`20260612_0531`, `20260618_0956`, `20260622_2352`, `20260703_0715`, `20260703_0933`,
`20260714_1156`, `20260804_0600`

# 도구 문법 혼용

## 증상

- 커밋 메시지 앞뒤에 `@` 문자가 박혔다. Bash 도구에서 PowerShell here-string
  (`@'...'@`)을 쓴 결과. `git commit --amend -F - <<'EOF'`로 재작성해야 했다
- `gh issue create --body @'...'@`가 본문 내 큰따옴표로 인자 경계가 깨져 실패
- `git commit -F - @'...'@` — `-F -`는 stdin을 읽는데 here-string을 인자로 붙였다.
  git이 메시지 전체를 pathspec으로 읽고 "did not match any file(s)"
- PowerShell에서 `gh --jq '.[] | "\(.number)\t\(.title)"'` — 인자 안의 큰따옴표가
  네이티브 호출에서 사라져 jq가 깨진 식을 받았다
- `npx tsc`가 엉뚱한 패키지를 받아와 실행. `./node_modules/.bin/tsc`를 써야 했다
- slash command에서 `$1`이 비어 `gh issue view` 가 "accepts 1 arg(s), received 0"
- Git Bash에서 `git cat-file -e origin/main:.env.example`, `git show origin/main:.env.example`이
  실패했다. MSYS가 `:`가 든 인자를 Windows 경로로 바꿔 git에 `origin\main;.env.example`이
  전달됐다. 실패를 "main에 파일 없음"으로 읽어 이미 main에 있는 변경을 오너 결정 항목으로 올렸다

## 근본 원인

이 환경에는 셸이 둘이다. Bash 도구는 POSIX sh, PowerShell 도구는 PowerShell 5.1.
다중행 문자열 문법이 서로 호환되지 않는데 겉보기에는 둘 다 "따옴표 블록"이라
헷갈린다.

주목할 점: **이미 메모리에 규칙이 있는데도 재발했다** (`20260804_0600`,
"기존 메모리에 있던 항목인데 또 틀렸다"). 기억에 의존하는 대책이 실패한 증거다.

## 올바른 방법

- Bash 도구 → heredoc `<<'EOF' ... EOF`
- PowerShell 도구 → here-string `@'...'@` (닫는 `'@`는 반드시 0열)
- 다중행 텍스트를 명령에 넘길 일이 있으면 인라인을 포기하고 **파일로 쓴 뒤 파일 인자**를
  쓴다. `gh --body-file`만이 아니라 `git commit -F <파일>`도 같다 — 이 규칙을 `gh`에만
  적용했다가 커밋 메시지에서 그대로 재발했다
- 셸별 quoting이 필요한 인자(jq 식 등)도 같다. PowerShell에서 큰따옴표가 든 인자를
  네이티브 명령에 넘기지 말고, `gh`는 `--template`이나 기본 표 출력을 쓴다
- 로컬 바이너리는 `npx`가 아니라 `./node_modules/.bin/<name>`으로 직접 호출
- slash command 위치 인자는 0-based. 인자가 하나면 `$ARGUMENTS`를 쓴다
- Git Bash에서 `<rev>:<path>` 인자를 쓰는 git 명령은 `MSYS_NO_PATHCONV=1`을 붙인다.
  존재 확인이 실패하면 "없다"로 결론 내기 전에 에러 문구부터 본다

## 자동화 후보

메모리 규칙이 이미 실패했으므로 훅이 필요하다.

- PreToolUse(Bash) 훅: 명령 문자열에 `@'` 또는 `'@`가 있으면 차단하고
  heredoc 예시를 출력
- 같은 훅에서 `npx tsc` / `npx eslint` 패턴 경고

**위 후보만으로는 부족하다** (2026-08-30 재발 기준). 그날의 두 건은 둘 다 PowerShell
도구에서 났다 — Bash 입력을 보는 훅은 아무것도 못 잡는다. 다시 설계한다면 셸이 아니라
**인자 모양**을 봐야 한다.

- PreToolUse(PowerShell) 훅: stdin을 읽는 플래그(`-F -`, `--body -`, `-f -`)에 인라인
  문자열이 붙어 있으면 차단
- 같은 훅: 네이티브 명령 인자 안에 큰따옴표가 든 경우 경고 (`--jq`, `--format`)

## 재발 이력

- `20260612_0531`
- `20260618_0956`
- `20260622_2352`
- `20260703_0715`
- `20260703_0933`
- `20260714_1156`
- `20260804_0600`
- `20260830_0420_ticket-19-followup-o5-o6`
- `20260922_0625_public-release-prep-cleanup` (MSYS 경로 변환으로 `rev:path` 조회 실패를 "파일 없음"으로 오판)

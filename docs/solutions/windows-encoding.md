# Windows 인코딩 — mojibake, BOM, cp949

## 증상

세 가지 얼굴로 나타난다.

1. **한글 메시지 깨짐(mojibake)** — Java 에러 메시지의 한글이 깨진 바이트로 저장되어
   API 응답으로 그대로 노출 (`AnonymousAnalysisUsageService`, `ClientIpResolver`)
2. **BOM** — 파일 맨 앞의 보이지 않는 3바이트가 spotless/컴파일을 깨뜨림
3. **cp949** — `UnicodeEncodeError: 'cp949' codec can't encode character` — Python
   스크립트가 이모지를 출력하려다 터미널 인코딩에서 실패

## 근본 원인

한국어 Windows의 기본 콘솔 인코딩이 **cp949**이고, PowerShell의 `Set-Content`류
기본 인코딩도 UTF-8이 아니다. AI 도구가 UTF-8로 쓴 내용이 cp949 경로를 한 번
통과하면 깨진다. Mac/Linux에서는 재현되지 않는 환경 고유 문제다.

읽기에서도 같은 일이 생긴다 — PowerShell 기본 디코딩이 UTF-8 화살표를 물음표로
보여줘서, 패치 대상 문자열이 매칭되지 않아 편집이 조용히 실패한 적이 있다
(`20260621_0648`).

## 올바른 방법

- Python 스크립트 실행은 항상 `python -X utf8 ...`
- PowerShell로 파일을 쓸 때는 `-Encoding utf8`을 명시 (`Out-File`, `Set-Content`)
- 한글이 들어가는 Java/TS 파일은 저장 후 실제로 읽어 확인한다. "썼다"로 끝내지 않는다
- 한글 사용자 메시지를 새로 만들지 말고, 이미 정상인 기존 상수를 재사용한다

## 자동화 후보

재발 8회.

- 커밋 전 스캔 훅: 스테이징된 파일에서 BOM(`EF BB BF`)과 mojibake 시그니처를
  검출하면 차단
- 기존 `scripts/hooks/Check-NoRawText.ps1`과 같은 자리에 붙이면 된다

## 재발 이력

- `20260610_1104`
- `20260617_1250`
- `20260618_0015`
- `20260618_0956`
- `20260619_0823`
- `20260619_1110`
- `20260621_0517`
- `20260621_0648`
- `20260906_eval-case-criteria-review` (python print 한글 CP949 깨짐, PYTHONIOENCODING=utf-8로 해결)
- 그 외 1건은 2026-08-25 소급 집계분으로, 개별 dev-log에 귀속하지 못했다.
  당시 집계는 9회였고 위 8건이 확인된 것이다

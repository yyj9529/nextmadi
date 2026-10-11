# Exec plan: 롤플레이 음성 턴 (C6-04) — multipart 업로드 방식

Status: **설계 단계, 구현 전.** 오너 결정 완료: 방식 B(음성 파일을 turns API에 multipart로 업로드),
공유 녹음 컨트롤러(`recorder-controller.ts`)는 수정하지 않는다.

## Context

로컬 전 기능 검증(`2026-10-07-local-full-verification.md`)에서 C6-04가 X였다. 롤플레이 화면의
마이크 버튼이 항상 비활성이고(`RoleplayExperience.tsx` 상단 주석: 음성 캡처는 티켓 범위 밖),
`docs/screens/s12.md`는 활성 마이크와 `POST /practice/sessions/{id}/turns` multipart 업로드를 요구한다.

이미 있는 것:

- 백엔드 `PracticeTurnController.submitAudio`(multipart `audio` 파트)와 `PracticeTurnService.submitAudioTurn`.
  서버가 Whisper 전사, 빈 전사 또는 신뢰도 0.5 미만 판정(턴 미소비 + retry_prompt), 신뢰도 저장,
  멱등성 키 처리를 한 요청에서 모두 한다.
- 공유 녹음 스택 `useVoiceRecorder` / `VoiceRecorderController` / `recorder-machine`(S02, S04가 사용).
  권한 요청, 포맷 지원 확인, VAD 3.5초 무음 정지, 58초 하드컷, 마이크 해제가 검증되어 있다.

없는 것: BFF turns 라우트와 `submit-turn.ts`의 multipart 경로, 롤플레이 화면의 녹음 연결.

## Decision

1. 서버 계약은 s12.md 그대로 간다. 브라우저가 녹음 Blob을 BFF turns 라우트에 multipart로 올리고,
   BFF는 그대로 백엔드에 전달한다. 전사와 신뢰도 판정은 서버 한 곳에만 둔다.
2. 공유 컨트롤러는 건드리지 않는다. 이유: S02/S04 음성 입력과 마이크 해제 로직이 같은 코드에
   의존하고, 이 로직은 실측 버그(#143, timeslice 헤더 문제)를 거쳐 안정화된 부분이다.

## 컨트롤러를 건드리지 않는 방법: fetch 어댑터

컨트롤러는 `deps.fetch`를 주입받는다(`VoiceRecorderDeps.fetch`). 컨트롤러가 녹음을 마치면
`transcribe()`가 `deps.fetch("/api/transcriptions", { body: FormData(audio) })`를 부르고, 응답의
`transcript`가 비어 있지 않으면 `confirm`을 거쳐 `onTranscript`를 호출한 뒤 idle로 돌아간다.

롤플레이 전용으로 `deps.fetch`만 갈아끼운다.

```
브라우저 deps (browserDeps())
  + fetch = 어댑터
        요청 URL이 /api/transcriptions 이면:
          1. init.body(FormData)에서 audio Blob을 꺼내 pendingAudio에 보관
          2. 네트워크에 나가지 않고 { transcript: "(audio)" } 200 응답을 즉시 돌려줌
        그 외 URL이면: 에러를 던진다(예상치 못한 요청이 실서버로 나가는 것을 막음)
  → 컨트롤러는 "전사 성공"으로 보고 onTranscript 호출
  → onTranscript에서 pendingAudio를 multipart로 POST /api/practice/sessions/{id}/turns
```

효과:

- 컨트롤러, 상태머신, `VoiceInput`, S02/S04 코드 변경 0줄.
- 롤플레이에서 전사는 서버가 한 번만 한다. 어댑터가 돌려주는 값은 상태머신을 통과시키기 위한
  자리표시이고, 화면에 보이거나 서버로 가지 않는다.
- Blob이 pendingAudio에 남아 있어서, 업로드가 네트워크 오류로 실패하면 같은 Blob과 같은
  Idempotency-Key로 재전송할 수 있다. 사용자가 다시 말하지 않아도 된다.

이 방식의 약점(정직하게):

- 컨트롤러 내부 계약(URL 문자열, FormData 필드명 `audio`)에 기대는 이음새다. 컨트롤러가 이 계약을
  바꾸면 롤플레이 음성이 조용히 깨질 수 있다. 완화: 어댑터가 예상 밖 URL이면 즉시 던지고,
  컨트롤러 계약을 고정하는 단위 테스트를 둔다(아래 Tests).
- 상태머신의 "변환 중" 문구는 롤플레이에서 보이지 않는다(어댑터가 즉시 응답). 롤플레이 화면은
  자체 processing 표시를 쓴다.
- 대안으로 컨트롤러에 선택적 의존성 하나를 추가하는 방법(기본 동작 불변, 약 10줄)이 있다.
  이음새가 사라져 더 깔끔하지만 공유 코드 변경이다. 이번 결정은 "안 건드린다"이므로 채택하지
  않았고, 어댑터가 실제로 문제를 일으키면 이 대안으로 전환한다.

## Changes

| 파일 | 변경 |
|---|---|
| `src/lib/practice/submit-turn.ts` | `submitAudioTurn`(multipart 전달) 추가. 토큰 발급, 응답 매핑, 에러 클래스는 기존 것을 공유하도록 내부 함수로 분리. 텍스트 경로 동작 불변 |
| `src/app/api/practice/sessions/[session_id]/turns/route.ts` | `content-type`이 multipart면 `audio` 파트를 꺼내 `submitAudioTurn` 호출. 비어 있거나 4MB 초과면 400/413(`/api/transcriptions`와 같은 한도). Origin 검사, 인증, Idempotency-Key 전달은 기존 그대로. JSON 경로 불변 |
| `src/lib/voice/roleplay-voice-deps.ts` (신규) | 위 fetch 어댑터. `createRoleplayVoiceDeps(base)`가 deps와 `takeAudio()`를 돌려줌 |
| `src/app/(app)/practice/[session_id]/RoleplayExperience.tsx` | `useVoiceRecorder({ deps, onTranscript })` 연결. `sendTurn`이 텍스트 또는 audio를 받도록 일반화(기존 `sendingRef`, `turnKeyRef`, 응답 처리 공유). 마이크 버튼 활성화, 녹음 중 표시(펄스 + 경과시간), 취소, 미지원/권한 거부 시 키보드 입력 안내 |
| `docs/screens/s12.md` | 변경 없음(스펙 그대로 구현). 파형은 아래 열린 질문 참고 |

## 화면 동작 정리

- idle: 마이크 활성. 키보드 입력도 그대로 사용 가능.
- 녹음 중: 마이크가 정지 버튼 역할, 경과시간 표시, 취소 버튼. 3.5초 무음이면 자동 정지.
- 업로드/처리 중: 기존 "..." 입력 표시, 마이크와 입력 비활성(`processing`).
- 서버가 `turn_consumed=false`(신뢰도 0.5 미만): 기존 notice에 `retry_prompt`를 보여주고 턴 증가 없음.
  이때 pendingAudio를 비우고 Idempotency-Key도 새로 만든다(서버 응답이 도달했으므로 논리 턴 확정).
- 빈 전사(무발화): 서버가 `turn_consumed=false`가 아니라 **422 empty_transcript**로 응답한다(아래 허점 1).
  BFF가 422를 그대로 전달하고, 화면은 "다시 말해볼까요" 안내 + 새 녹음 시 새 키.
- 네트워크 오류(결과 불명): 재전송 정책은 아래 허점 2의 결정에 따른다(이전 초안의 "같은 Blob, 같은 키 재전송"은 그대로는 안전하지 않다).
- 권한 거부/미지원/녹음 실패: 컨트롤러 상태(`permission_denied`, `unsupported`, `error_recording`)를 읽어
  키보드 입력 안내로 매핑(`VoiceInput`의 문구와 맞춤). 상태머신이 만드는 `error_empty`, `error_transient`,
  `error_rate_limited`는 어댑터 때문에 롤플레이에서 발생하지 않는다.
- 녹음 중에는 텍스트 입력과 전송 버튼을 비활성화한다(허점 4).

## Review: 허점과 반영 (2026-10-09 코드 대조)

번호는 심각도 순이다. "확인"은 코드로 직접 읽은 사실이다.

1. **빈 전사 처리가 계획과 다르다 (확인).** `TranscriptionService.transcribe`는 빈 전사에 422 `empty_transcript`를
   던진다. 그래서 `PracticeTurnService`의 빈 전사 분기(`!hasText -> completeWithoutTurn`)는 음성 경로에서
   도달 불가이고, 낮은 신뢰도만 `turn_consumed=false`가 된다. 현재 `submitTurn`은 404/409 외 모든 오류를
   502로 뭉개므로 422가 "메시지를 보내지 못했어요"로 보이고 "다시 말해볼까요"가 사라진다.
   반영: `SubmitTurnError`가 상태와 `error_code`를 보존하고, 라우트가 400/408/422/429/503을 구분해 전달한다.
2. **같은 멱등성 키 재전송이 실패 후에는 409로 막힌다 (확인).** 서버는 실패하면 요청 행을 `failed`로
   남기고(`markFailed`), `findReplay`는 `failed`를 재생하지 않고 비운다. 같은 키로 다시 오면
   `reserveRequest`가 중복 키 예외를 내고, 재생할 게 없어 "이미 처리 중" 409를 던진다. 그런데 현재
   `sendTurn`은 409를 "이미 끝난 세션"으로 보고 결과 화면으로 보낸다. 음성에서는 STT/LLM 실패가 흔해서
   재시도가 곧바로 이 오분류로 이어진다. 텍스트 턴에도 있는 잠복 버그이고, 이번 변경이 노출 빈도를 높인다.
   결정 필요(아래 Decision needed 1).
3. **키가 페이로드와 묶여 있지 않다.** 현재 `turnKeyRef`는 결과 불명이면 유지된다. 음성 재시도 실패 후 사용자가
   새로 녹음하거나 텍스트로 바꾸면 다른 내용이 같은 키로 나가 이전 결과를 재생받거나 충돌한다.
   반영: 키를 `{ key, payload id }`로 묶고, 새 녹음 시작 또는 페이로드 변경 시 키와 pendingAudio를 폐기한다.
4. **녹음 중 텍스트 전송이 가능하다.** 녹음하다가 텍스트를 보내면 `sendingRef`가 막혀 녹음 결과가 조용히
   버려지거나 두 턴이 겹친다. 반영: 녹음/정지 중 텍스트 입력 비활성, 어댑터 전송 시 `sendingRef`가 잡혀
   있으면 버리지 않고 pendingAudio로 보관해 재시도 버튼을 보인다.
5. **로컬 mock STT로는 이 설계의 핵심 장점을 검증할 수 없다 (확인).** `MockOpenAiTranscriptionClient`는 항상
   `"[MOCK] 마트에서 ..."`(한국어 고정)를 돌려주고 신뢰도는 비어 있다(null). 그래서 낮은 신뢰도 분기는
   실제 OpenAI 키 없이는 재현되지 않고, 롤플레이 대화 내용도 항상 같은 한국어가 된다. 반영: 신뢰도 분기는
   백엔드 단위 테스트(이미 있는지 확인 필요)로, 실제 발화 확인은 키가 있는 환경에서 오너가 한다. 검증 계획서의
   "mock으로 전체 흐름" 항목에 이 한계를 적는다.
6. **요청 하나가 길어진다.** 서버가 STT(30s) + 코치(15s) + 피드백(10s) + TTS(15s)를 순차로 처리하므로 최악
   약 70초다(`AI_PIPELINE.md` 타임아웃, 재시도는 별도). 텍스트 턴 경로에는 클라이언트와 BFF 어느 쪽에도
   타임아웃이 없다. 반영: BFF 백엔드 호출과 클라이언트 fetch에 타임아웃(약 90초, 구현 때 확정)을 두고,
   배포 환경의 함수 최대 실행 시간 제한은 배포 문서에서 확인한다(미확인).
7. **Spring multipart 기본 한도가 빠듯하다 (추정).** `src/main/resources`에 multipart 설정이 없어 Spring Boot
   기본값(파일 1MB)이 적용되는 것으로 보인다. 컨트롤러 주석의 실측 "3초 ≈ 48KB"로 환산하면 58초는 약
   930KB로 한도의 약 89%다. `/transcriptions`에서도 같은 위험이 있었는데 문제 보고는 없었다. 반영: 58초
   하드컷 녹음으로 직접 확인하고, 넘으면 한도를 올리는 설정을 별도 처리한다.
8. **STT 일일 한도 (해소).** `/transcriptions`의 한도는 익명용이고 `TranscriptionService`가 아닌 엔드포인트에
   걸려 있다(컨트롤러 주석). 롤플레이 음성 턴에는 STT 전용 한도가 없고, 세션 하루 2회 한도와 턴 수 상한으로
   간접 제한된다.
9. **어댑터 이음새.** 아래 Tests의 계약 고정 테스트로 완화(변경 없음). 추가로 `onTranscript`가 어댑터 응답
   직후 동기적으로 호출되는지는 상태머신(`transcribed -> confirm -> reset`)을 읽어 확인했다.

> 정정 (구현 중 확인): 허점 2의 노출 범위는 처음 설명보다 좁다. 클라이언트는 서버 응답을 받으면 키를
> 버리므로(`turnKeyRef = null`), 5xx 같은 "서버가 응답한 실패" 뒤의 재시도는 새 키를 쓴다. 같은 키가
> 재사용되는 경우는 응답을 못 받은 경우(네트워크 단절, 클라이언트 시간 초과)이고, 서버가 그 사이 이미
> 실패로 마감했다면 이때 409가 난다. 그래도 키를 재사용하는 이유가 바로 이 경우라서 수정은 유효하다.
> 남는 한계: `processing`으로 멈춘 요청(서버 크래시)과 "세션 종료"는 둘 다 error_code `conflict`의
> 409라서 BFF/화면이 구분하지 못한다. 별도 후속 후보.

### Decision (결정됨: 가)

1. 허점 2의 해결 방식.
   - 가. 백엔드 수정: 같은 키가 `failed`이면 요청 행을 다시 `processing`으로 되살려 재처리한다. 정석이고
     텍스트 턴 잠복 버그도 같이 고쳐진다. 롤플레이 상태 변경이라 CLAUDE.md의 2-에이전트 리뷰 대상이다.
   - 나. 프런트만: 서버에 도달한 실패 뒤에는 항상 새 키, 결과 불명(네트워크 단절)일 때만 같은 키. 서버가 "처리 중"과
     "세션 종료"를 같은 409로 주므로 BFF가 메시지로 구분해야 하고, 실제로 처리 중이던 요청과 겹치면 턴이 두 번
     생길 수 있다.
   - 제안: 가. 변경이 작고(분기 하나) 근본 원인을 고친다.

## Open questions

1. (결정됨) 파형: s12.md는 "펄스 + 라이브 파형 + 취소 버튼"을 요구하지만 공유 `VoiceInput`에는 파형이 없다.
   파형은 컨트롤러가 음량 값을 노출해야 해서 "컨트롤러 불변" 조건과 충돌한다. 이번엔 펄스 + 경과시간만
   구현하고 파형은 이슈 #180으로 분리했다(S02, S04 포함 적용).
2. (허점 6으로 이동) 업로드 요청 타임아웃.
3. 백엔드 audio 에러 코드 확인 결과: 잘못된 WebM 400 `validation_failed`(재시도 불가), 빈 전사 422
   `empty_transcript`, STT 타임아웃 408 `timeout`, 제공자 429 `provider_429`, 제공자 5xx 503 `provider_5xx`.
   사용자 문구 매핑은 구현 때 표로 만든다.
4. (허점 8로 해소) 로그인 사용자 STT 일일 한도는 없다.

## Tests

- `submit-turn`: 가짜 fetcher로 multipart 본문에 `audio` 파트가 있고 `content-type`을 수동 지정하지
  않았는지(경계 문자열 유지), Idempotency-Key 전달, 404/409 에러 매핑.
- turns 라우트: multipart 정상, audio 없음 400, 4MB 초과 413, 비로그인 401, Origin 불일치 403, JSON 경로 회귀 없음.
- 어댑터: `/api/transcriptions` 호출 시 Blob 보관 + 네트워크 미호출 + 비어 있지 않은 transcript 응답,
  그 외 URL은 throw. 실제 `VoiceRecorderController`(가짜 deps)에 꽂아 녹음 종료 시 `onTranscript`가
  정확히 한 번 호출되는 계약 고정 테스트 — 컨트롤러가 바뀌어 이음새가 깨지면 여기서 실패한다.
- 수동(오너 브라우저): 마이크 권한 허용/거부, 정상 음성 턴, 무음/소음(턴 미소비 안내), 오프라인에서
  전송 후 복구 재시도, 더블탭/새로고침 중복 턴 없음. 마이크는 내장 브라우저에서 막혀 있어 오너 확인.

## Out of scope

- iOS Safari mp4/AAC (#133), S12b 추천 표현 재생(#121), 파형(#180).

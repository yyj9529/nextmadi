# Exec plan: Voice Input Flow (Record → Transcribe → Confirm) — #36

Status: **계획 수립 2026-08-04.** owner 확인 완료 항목: 모델 배정(Opus 5 설계+구현, Codex 리뷰),
S04 전사 확인 UI(S05a 모달 프리필), 저신뢰 전사 경고 없음.

## Goal

S02(체험)와 S04(홈)가 공유하는 음성 입력 플로우를 실경로로 만든다. 현재 S04 마이크는
녹음 없이 목 결과로 라우팅하고(`HomeAnalysisCard.tsx`), S02 마이크는 핸들러가 없다.
녹음 → `POST /transcriptions` → 전사문 확인/편집 → `POST /analysis`의 2-스텝 흐름을
완성해 로컬에서 음성 진입로가 실제로 돈다.

## Source specs

- `docs/screens/s02.md` US1-4 — 2-스텝 결정(2026-06-10). 오전사가 LLM 호출이나 익명 2회
  한도를 소모하지 않게 하는 것이 이유. UI states 표의 "STT processing" 행.
- `docs/screens/s04.md` US1-3 — 홈 마이크는 S02와 같은 흐름.
- `docs/AI_PIPELINE.md` Stage 1 — Whisper, 30s 타임아웃, `stt_transcription` 로깅,
  `stt_confidence`는 nullable(모델 의존).
- `docs/api/openapi.yaml` `POST /transcriptions` — multipart `audio`, 200
  `{ transcript, stt_confidence }`, 400/429.
- ADR-010 — BFF `X-Internal-Auth` 핸드오프(user_id XOR session_token).

백엔드(#29)는 이미 완성 상태다. `TranscriptionController` → `TranscriptionService`가
Whisper 호출·30s 타임아웃·`ai_request_logs` 기록·에러 코드 매핑을 모두 갖고 있고,
`WebmOpusInspector`가 WebM/Opus 형식과 60초 상한을 서버에서 강제한다. 이 티켓은
**프론트엔드 + BFF 라우트**가 범위다(단, 아래 리스크 1의 결과에 따라 inspector 예외).

## 상태머신 (설계 산출물)

한 개의 훅 `useVoiceRecorder`가 아래 상태를 소유한다. S02/S04는 이 훅과 표시 컴포넌트만
공유하고, 전사 확정 후 목적지만 다르다.

```
idle
 ├─ tap ─────────────► requesting_permission
 │                      ├─ granted ─────► recording
 │                      ├─ denied ──────► permission_denied  (종료 상태, 텍스트 입력 유도)
 │                      └─ unsupported ─► unsupported        (종료 상태, 텍스트 입력 유도)
 │
recording  (경과시간 타이머 + VAD 감시)
 ├─ tap again ───────► stopping
 ├─ VAD 무음 감지 ───► stopping
 ├─ 60s 하드컷 ──────► stopping
 └─ 무발화 8s ───────► stopping   (전사 결과가 비면 error_empty로 귀결)
 │
stopping   (MediaRecorder onstop / 마지막 chunk 대기)
 └─ blob 완성 ───────► transcribing
 │
transcribing  (POST /api/transcriptions)
 ├─ 200 + 비어있지 않은 transcript ─► confirm
 ├─ 200 + 빈 transcript / 400 ─────► error_empty
 ├─ 429 / 408 / 5xx / 네트워크 ────► error_transient
 └─ 사용자가 취소 ─────────────────► idle (AbortController)
 │
confirm    (S02: 입력창 프리필 / S04: S05a 모달 프리필)
 └─ 사용자가 제출 ───► 기존 POST /analysis 경로 (이 티켓 범위 밖, 이미 구현됨)

error_empty      ─ "다시 말해볼까요?" + 재녹음 / 텍스트 입력
error_transient  ─ user_message 표시 + 재시도(같은 blob 재전송) / 재녹음
permission_denied─ "마이크 권한이 필요해요" + 텍스트 입력 CTA
unsupported      ─ "이 브라우저는 녹음을 지원하지 않아요" + 텍스트 입력 CTA
```

불변식:
- 마이크 스트림 트랙은 `stopping` 진입 시 **항상** `stop()` 한다(종료 상태·에러·언마운트
  포함). 녹음 표시등이 남는 것은 신뢰를 크게 깎는다.
- `transcribing`에서 나가는 모든 경로는 `AbortController`를 정리한다.
- 전사문은 사용자 데이터다 — 로그에 남기지 않는다(SECURITY.md).

VAD 파라미터(초기값, 실사용 후 튜닝):
- Web Audio `AnalyserNode` RMS, 임계 0.01(≈ -40dBFS), 무음 **2.0초** 지속 시 정지.
- 녹음 시작 후 **1.5초**는 감시하지 않는다(말 시작 전 무음으로 즉시 끊기는 것 방지).
- 한 번도 임계를 넘지 않으면 **8초**에 자동 정지 → 빈 전사 경로로 귀결.

## Files expected to change

- `src/lib/voice/use-voice-recorder.ts` (신규) — 위 상태머신. 브라우저 API는 주입 가능하게
  해서 테스트에서 MediaRecorder를 가짜로 바꾼다.
- `src/lib/voice/transcribe.ts` (신규) — BFF → Spring 프록시 lib.
  `src/lib/analysis/submit-analysis.ts`의 XOR 인증 패턴을 그대로 따른다.
- `src/lib/voice/*.test.ts` (신규) — 상태 전이 + 에러 매핑.
- `src/app/api/transcriptions/route.ts` (신규) + `route.test.ts` — multipart 프록시.
  같은-Origin 검사와 익명 쿠키 처리는 `api/analysis/route.ts`,
  multipart 취급은 `api/practice/sessions/[session_id]/turns/route.ts`를 참고한다.
- `src/components/app/VoiceInput.tsx` (신규) — 마이크 버튼 + 상태별 표시(경과시간,
  "변환 중...", 에러 문구). S02/S04 공유.
- `src/components/app/AnalysisModals.tsx` — `TextInputSheet`에 `initialText` prop 추가.
  `AnalysisLoadingModal`의 `autoRoute`/`mockAnalysisResultPath` 목 경로 제거.
- `src/app/(app)/home/HomeAnalysisCard.tsx` — 가짜 마이크 제거, `VoiceInput` 배선,
  전사 확정 시 S05a 모달을 프리필해서 연다.
- `src/app/(app)/try/TryExperience.tsx` — 마이크 버튼 배선, 전사문을 입력창에 채운다.
- `src/app/(app)/app.css` — 녹음/변환 상태 스타일.
- `backend/.../WebmOpusInspector.java` — **조건부**(리스크 1 참고).

## Acceptance criteria

s02.md / s04.md G-W-T 기준:

1. S02 마이크 탭 → 녹음 → 정지 → "변환 중..." → 전사문이 입력창에 채워지고 편집 가능 →
   "분석하기" 제출 시 `POST /analysis`(JSON)로 나간다. 오디오가 `/analysis`로 가지 않는다.
2. S04 마이크 탭 → 같은 흐름 → 전사문이 S05a 모달에 프리필된 상태로 열린다.
3. 정지 조건 3가지가 모두 동작: 재탭 / VAD 무음 / 60초 하드컷.
4. 마이크 권한 거부 → 텍스트 입력 유도 문구 + CTA. 미지원 브라우저도 동일.
5. 빈 전사 → 재시도 유도. 429/타임아웃/네트워크 → 재시도 가능한 에러 표시.
6. 정지·에러·언마운트 어느 경로로 끝나도 마이크 트랙이 해제된다.
7. 익명(S02) 호출이 `X-Internal-Auth`에 `session_token`을, 로그인(S04) 호출이 `user_id`를
   담는다(XOR).

## Test plan

- **단계 0 (구현 전 실측) — 완료 2026-08-04.** Playwright + 시스템 Chrome +
  `--use-fake-device-for-media-stream`으로 실제 MediaRecorder blob을 만들어 진짜
  `WebmOpusInspector`에 통과시켰다. 결과는 리스크 1 참고. 백엔드 변경 없음,
  timeslice 금지 제약 확정. 신규 테스트 2개 포함 inspector 테스트 8/8 통과.
- 단위(TDD): `use-voice-recorder` 상태 전이 — 권한 거부, 60초 컷, VAD 정지, 빈 전사,
  429, abort. MediaRecorder/getUserMedia는 주입 가짜.
- 라우트: `/api/transcriptions` — 미인증 익명 통과(session_token 발급), 로그인 사용자
  user_id 경로, Origin 불일치 403, audio 누락 400, 백엔드 429 패스스루.
- 브라우저(`/ui-verify`): S02·S04 각각 녹음→전사→편집→분석 왕복 1회 + 권한 거부 1회.
  OpenAI STT mock(#117)이 켜져 있어 비용 0. 콘솔 에러 0 확인.

## Risk areas

1. ~~**MediaRecorder WebM에 Duration 메타데이터가 없을 가능성 (최우선)**~~
   **해소됨 2026-08-04 — 백엔드 변경 불필요. 대신 프론트 제약이 하나 생겼다.**
   실제 Chrome MediaRecorder 녹음 2종을 만들어 진짜 `WebmOpusInspector`에 통과시켰다.
   - `recorder.start()` (timeslice 없음) → Segment 크기와 Duration이 blob 확정 시 패치된다.
     Duration은 4바이트 float(2940, TimecodeScale 1e6 → 2.94초)로 기록되고, inspector의
     `readFloat`가 4바이트/8바이트를 모두 다뤄 **통과**한다.
   - `recorder.start(1000)` (timeslice) → Segment가 unknown-size로 남고 Duration이 아예
     기록되지 않는다 → **400**. (거부 사유는 duration 부재가 아니라 unknown-size 경계에서
     파서가 먼저 걸리는 것 — 사용자에겐 동일한 `validation_failed`.)
   **결론: 프론트엔드는 `MediaRecorder.start()`에 timeslice를 주지 않는다.** 60초 상한이
   있어 메모리에 통째로 들고 있어도 문제없다(3초 ≈ 48KB → 60초 ≈ 1MB 미만, Vercel body
   상한에도 여유).
   회귀 방지로 실녹음 픽스처 2개와 `WebmOpusInspectorRealRecordingTests`를 추가했다.
   기존 백엔드 테스트는 Duration을 손으로 써넣은 합성 WebM만 써서 이 갈래가 보이지 않았다.

   <sub>원래 가설(기록 보존): "MediaRecorder는 라이브 먹싱이라 Duration을 비워두므로
   실기기 녹음이 400으로 튕길 것이다. 그렇다면 백엔드가 크기 기반 검증으로 완화해야 하고
   그건 owner 승인 사항이다." — 절반만 맞았다. timeslice 없이 녹음하면 Chrome이 blob
   확정 시 헤더를 되돌아가 패치한다.</sub>

2. **비용** — 로컬은 `openai.mock.enabled: true`라 0. 실키 검증은 owner 승인 후에만.
3. **업로드 크기** — Vercel 함수 body 상한은 openapi가 W4 확인 항목으로 명시했다. 로컬
   동작에는 영향 없으므로 배포 전 확인 항목으로만 기록한다.
4. **AI 경로 + 익명 인증을 건드린다** — CLAUDE.md 9절 2-에이전트 핸드오프 대상.
   구현 후 Codex(또는 `spec-reviewer`)가 diff를 s02/s04 스펙과 대조한다.

## Decision log

- **모델**: 카드는 Opus 4.8 설계 → Sonnet 4.6 구현이지만, owner 확인 후 Opus 5가 설계와
  구현을 모두 맡는다. 카드가 요구한 "구현자와 다른 계열의 리뷰"는 Codex 리뷰로 유지된다.
- **S04 전사 확인 UI**: S05a `TextInputSheet` 프리필. 500자 카운터·검증·제출이 이미
  실경로로 구현돼 있어 재사용하는 편이 중복이 없고, S02(입력창 프리필)와 멘탈 모델도 같다.
- **저신뢰 전사 경고 없음**: S02/S04는 사용자가 전사문을 보고 고친 뒤 제출하므로 확인
  단계가 이미 방어다. `AI_PIPELINE.md`의 0.5 임계는 S12용 placeholder이고 튜닝 데이터가
  없어 근거가 약하다. `stt_confidence`는 백엔드 로깅용으로만 둔다.
- **빈 전사만 재시도 유도**: 이슈 본문의 "STT empty result: prompt retry"는 구현하되
  confidence 기반 분기는 넣지 않는다.
- **waveform 생략 (owner 결정, spec-check 후)**: `s02.md` UI states의 "변환 중... with
  waveform fade"에서 waveform을 뺀다. 전사 대기는 보통 1~2초이고, 그 시점에 파형을 그리면
  마이크가 아직 열려 있다는 오해를 준다. 시각적 무게는 녹음 상태(펄스 + 경과 시간)가 진다.
  `s02.md`를 결정에 맞춰 갱신했다.
- **S06 / S05a 스펙 충돌을 s05a 쪽으로 정리 (owner 결정, spec-check 후)**: `s06.md`는
  "S02·S04·S05a 제출 시 S06 모달"이라 했고 `s05a.md`는 "시트를 연 채 제출 버튼 스피너"라
  해서 서로 어긋났다. S05a가 이미 모달이라 그 위에 모달을 또 쌓는 대신 제자리 진행 표시를
  쓴다. S04는 직접 제출 경로가 없어(마이크·텍스트 모두 S05a 경유) S06을 열지 않는다.
  `s06.md`(Purpose·AC1·Related), `s05a.md`(US1-4·Related), `s04.md`(US1-3·UI states)를
  모두 갱신했다.
- **익명 토큰**: 스펙은 sessionStorage `phraselog_session_token`이라 적었지만 구현은
  이미 httpOnly 쿠키(`phraselog_anon_session`)로 갈라져 있다(#34/#35). 전사 라우트도
  같은 쿠키를 쓴다 — 여기서 새 방식을 만들지 않는다.

- **빈 전사를 422 `empty_transcript`로 분리 (owner 결정 2026-08-27, 리뷰 후)**:
  독립 리뷰에서 빈 전사가 규격 오류와 같은 `400 validation_failed`로 나가는 바람에
  "말소리를 알아듣지 못했어요" 안내가 실경로에서 도달 불가였다(리뷰 B1). 프론트가
  이미 브라우저에 `422 empty_transcript`를 내리고 있어서, 구분이 없는 홉은
  Spring → BFF 하나뿐이었다. 백엔드에 `emptyTranscript()` 팩토리를 추가해 두 홉이
  같은 계약 토큰을 쓰게 했다.
  검토한 대안: (1) 프론트가 `developer_hint` 문자열로 분기 — openapi가 그 필드를 계약이
  아니라 힌트로 정의했고 문구만 바뀌어도 조용히 깨져서 기각. (2) 400 전체를 빈 전사로
  간주 — 지금은 capability 게이트 덕에 규격 오류가 사실상 안 나지만 #133(mp4/AAC 수용)이
  열리면 전제가 무너져서 기각. (3) 백엔드가 `200 + 빈 문자열` 반환 — 프론트 변경이 0이라
  매력적이었으나, "200인데 실패"는 앞으로의 모든 소비자가 빈 문자열 검사를 기억해야 하는
  계약이라 기각.
  같이 고친 것: 무발화도 Whisper 과금이 발생하는데 `estimatedCost` 계산 전에 throw해서
  `ai_request_logs`에 비용이 null로 남고 있었다. 한도 없는 익명 경로(리뷰 B2)와 겹치면
  남용 비용이 로그에서 보이지 않는다.
  근본 원인도 함께 막았다: 라우트 테스트가 `TranscribeError`를 가짜로 대체해 status→분류
  매핑을 재구현하고 있었고, 백엔드 테스트는 잘못된 `validation_failed`를 고정하고 있었다.
  둘 다 실제 계약을 확인하도록 바꿨다.

- **익명 전사 한도 10회/일, 전용 테이블 (owner 결정 2026-08-27, 리뷰 후)**:
  `POST /transcriptions`가 로그인 없이 열려 있고 아무 한도가 없어 Whisper 비용이 무제한
  노출돼 있었다(리뷰 B2, 두 리뷰가 독립적으로 지적). BFF는 쿠키가 없으면 직접 발급까지 해서
  상태 없는 호출자도 통과했고, `x-client-ip`도 넘기지 않아 백엔드가 나중에 IP 한도를 넣어도
  키가 없었다. `openapi.yaml`은 이미 429를 계약에 넣어 뒀으므로 계약 미이행이기도 했다.
  **전사 카운터는 분석 카운터와 분리한다.** 공유하면 오전사 한 번이 분석 기회를 태워서,
  전사를 분석과 떼어낸 2-스텝 결정(2026-06-10) 자체가 무너진다. V009로
  `anonymous_transcription_usage`를 만들고 `anonymous_analysis_usage`의 예약/해제 패턴을
  그대로 본떴다.
  검토한 대안: 기존 테이블에 `purpose` 컬럼을 추가해 범용화 — 동작 중인 테이블의 PK와 기존
  코드를 건드려야 해서 기각(세 번째 용도가 생기면 그때 합친다). 메모리 스로틀 — 재시작 시
  초기화되고 비용 감사 흔적이 남지 않아 기각.
  한도 10회의 근거: 분석 2회 × 재녹음 여유 5회. 60초 상한이 있어 IP당 하루 최악이 10분치
  Whisper다.
  **한도는 컨트롤러에 건다.** `TranscriptionService`는 롤플레이 턴(`PracticeTurnService`)도
  쓰는 공용 기능이고 롤플레이는 자체 하루 한도가 따로 있다. 정책은 공개 엔드포인트가 소유한다.
  **무발화(422)는 한도를 소모한다.** 처음에는 모든 실패에 한도를 돌려주게 썼다가 되돌렸다 —
  무발화는 Whisper가 이미 과금된 뒤의 결과라, 돌려주면 무음을 반복 전송해 한도를 전혀 쓰지
  않고 비용만 태울 수 있다. 그러면 이 한도의 목적이 무너진다. 규격 오류(공급자 호출 전 차단)와
  공급자 오류·타임아웃(사용자 잘못 아님)만 돌려준다.
  **한도 초과는 상태코드가 아니라 토큰으로 판별한다.** 공급자 혼잡도 429(`provider_429`)라,
  상태코드로 뭉뚱그리면 "잠시 후 재시도"와 "오늘은 안 됨"이 섞인다. UI에 `error_rate_limited`
  상태를 더해 재시도 CTA 없이 텍스트 입력으로만 안내한다.

## Final outcome

구현 완료 2026-08-04, 리뷰 대기.

검증 결과:
- 단위 테스트 28개 신규(상태머신 14 / VAD 7 / transcribe lib 7) + BFF 라우트 9개. 전체
  `bun test` 159 pass / 1 fail — 실패한 `getLandingExamples`는 이 변경과 무관한 기존 실패다
  (main 트리에서도 동일하게 실패, `.env`의 backend URL이 설정돼 있어 "설정 없음" 케이스가
  성립하지 않는다). typecheck·lint·build 모두 통과.
- 브라우저(프로덕션 빌드 + 실제 Chrome + 가짜 마이크, 390x844):
  - **S02 정상 왕복 PASS** — 녹음 → `POST /api/transcriptions` 200 → 전사문이 입력창에
    채워지고(37/500) 제출 버튼 활성. 콘솔 에러 0. 분석 호출은 사용자가 확인한 뒤에만 나간다.
  - **S02 마이크 권한 거부 PASS** — 안내 문구 + 재시도 CTA 노출, API 호출 0회.
  - 증빙 스크린샷 3장(녹음 중 / 전사 확인 / 권한 거부).
- **S04 브라우저 검증 미완료** — 로그인 세션이 필요한데 로컬에서 NextAuth 세션 쿠키를
  위조하는 데 실패했다(JWTSessionError: decryption operation failed). owner의 로그인
  브라우저에서 1분 수동 확인이 필요하다. 코드 경로는 S02와 동일한 훅·컴포넌트이고
  S04 고유분은 전사문을 S05a 모달에 프리필하는 부분뿐이다.

게이트 2(코드 리뷰) 자체 점검에서 8건을 찾아 5건을 고쳤다: AudioContext suspended 시
VAD 오판(resume 추가), 취소 시 MediaRecorder 미정지, BFF→백엔드 타임아웃 부재,
녹음 중 정지 버튼이 막힐 수 있던 disabled 로직, 죽은 CSS. 수정 후 테스트 37/37,
typecheck·lint·build 통과, S02 브라우저 왕복 재확인 완료.
P1이던 **iOS Safari 막다른 길**은 owner 결정으로 능력 게이트(`capability.ts`)를 넣어 해소했다:
WebM/Opus를 만들지 못하는 브라우저는 **마이크 권한을 묻기 전에** 감지해 텍스트 입력으로
안내한다. 재시도 CTA를 주지 않는 이유는 다시 눌러도 같은 결과이기 때문이다. mp4/AAC 수용
자체는 백엔드 계약 변경이라 #133으로 분리했다.
Safari 경로는 `MediaRecorder.isTypeSupported`를 Safari처럼 바꿔치기해 브라우저에서 확인했다 —
권한 요청 0회, 업로드 0회, 콘솔 에러 0, 입력창은 그대로 사용 가능.

이 리뷰는 구현자 자신이 수행한 것이므로 Codex 독립 리뷰가 여전히 필요하다(CLAUDE.md 9절).

## What changed after execution

- **리스크 1의 가설이 절반만 맞았다.** 백엔드를 고쳐야 할 줄 알았는데, 실제로는 프론트가
  timeslice를 쓰지 않으면 그만이었다. 단계 0 실측을 구현 전에 넣은 판단은 옳았지만,
  결론은 예상과 반대였다 — "고쳐야 한다"가 아니라 "이렇게 쓰면 된다".
- **기존 dev 서버가 하이드레이션되지 않는 문제를 발견했다(이 변경과 무관).**
  `next dev`에서 `/try`의 textarea에 입력해도 React state가 반영되지 않는다. 내 변경을
  stash한 main 트리에서도 동일하게 재현되고, `.next` 삭제 후에도 남는다. 프로덕션 빌드
  (`next build && next start`)에서는 정상이다. 별도 이슈로 떼야 한다 — 로컬 개발 전반에
  영향을 준다.
- **프로덕션 모드를 로컬에서 띄우려면 `AUTH_TRUST_HOST=true`가 필요하다**(Auth.js
  UntrustedHost). Vercel에서는 자동 감지되므로 배포에는 영향이 없고, 로컬 검증 절차의
  메모다.

import type { VoiceRecorderDeps } from "./recorder-controller";

// 롤플레이 음성 턴용 녹음 어댑터. (C6-04, docs/exec-plans/2026-10-09-roleplay-voice-turn.md)
//
// S12는 전사를 따로 받지 않고 녹음 파일을 턴 API에 그대로 올린다 — 전사와 신뢰도 판정을 서버가
// 한 요청에서 처리한다(s12.md US2). 그런데 S02/S04가 공유하는 VoiceRecorderController는 녹음이
// 끝나면 반드시 `deps.fetch("/api/transcriptions", FormData(audio))`를 호출하고, 비어 있지 않은
// 전사문을 받아야 "확인" 단계로 넘어가 onTranscript를 부른다. 이 컨트롤러는 마이크 해제 같은
// 위험한 코드를 쥐고 있어 건드리지 않는다.
//
// 그래서 컨트롤러가 부르는 fetch만 갈아끼운다. 전사 요청이 오면 네트워크로 보내지 않고 녹음
// Blob만 챙긴 뒤, 컨트롤러가 "전사 성공"으로 읽을 자리표시 응답을 즉시 돌려준다. 호출 측은
// onTranscript 안에서 takeAudio()로 Blob을 꺼내 턴 API에 올린다. 자리표시 문자열은 화면에 보이지
// 않고 서버로도 가지 않는다.
//
// 이 파일은 컨트롤러의 내부 약속(URL, FormData 필드명 "audio")에 기댄다. 그 약속이 바뀌면 조용히
// 깨지는 대신 요청이 실패하도록, 예상 밖 요청은 던진다. 약속은 roleplay-voice-deps.test.ts에서
// 실제 컨트롤러로 고정한다.

/** 컨트롤러가 전사를 요청하는 경로(recorder-controller.ts transcribe()). */
const TRANSCRIPTION_PATH = "/api/transcriptions";

/** 컨트롤러가 전사문을 비어 있지 않게 받기만 하면 되는 자리표시. 어디에도 표시되지 않는다. */
export const PLACEHOLDER_TRANSCRIPT = "(audio)";

export type RoleplayVoiceDeps = {
  /** VoiceRecorderController / useVoiceRecorder에 넘기는 deps. */
  deps: VoiceRecorderDeps;
  /** 가장 최근에 가로챈 녹음을 꺼내며 비운다. 없으면 null. */
  takeAudio: () => Blob | null;
};

export function createRoleplayVoiceDeps(
  base: VoiceRecorderDeps,
): RoleplayVoiceDeps {
  let captured: Blob | null = null;

  const fetchAdapter: VoiceRecorderDeps["fetch"] = async (input, init) => {
    if (input !== TRANSCRIPTION_PATH) {
      throw new Error(`roleplay voice adapter: unexpected request to ${String(input)}`);
    }
    const body = init?.body;
    const audio = body instanceof FormData ? body.get("audio") : null;
    if (!(audio instanceof Blob) || audio.size === 0) {
      throw new Error("roleplay voice adapter: transcription request had no audio");
    }
    captured = audio;
    return Response.json({ transcript: PLACEHOLDER_TRANSCRIPT });
  };

  return {
    deps: { ...base, fetch: fetchAdapter },
    takeAudio: () => {
      const audio = captured;
      captured = null;
      return audio;
    },
  };
}

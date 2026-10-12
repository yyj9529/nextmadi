import { describe, expect, test } from "bun:test";

import {
  VoiceRecorderController,
  type VoiceRecorderDeps,
} from "./recorder-controller";
import {
  PLACEHOLDER_TRANSCRIPT,
  createRoleplayVoiceDeps,
} from "./roleplay-voice-deps";

// 어댑터는 VoiceRecorderController가 전사 요청에 쓰는 내부 약속(URL, 필드명 "audio")에 기댄다.
// 아래 계약 테스트는 가짜가 아니라 실제 컨트롤러를 태워서, 컨트롤러가 그 약속을 바꾸면 여기서
// 실패하게 한다 — 바뀐 채로 머지되면 롤플레이 음성만 조용히 깨진다.

class FakeStream {
  stopped = 0;
  getTracks() {
    return [{ stop: () => (this.stopped += 1) }];
  }
}

class FakeRecorder {
  state: "inactive" | "recording" = "inactive";
  mimeType = "audio/webm;codecs=opus";
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  start() {
    this.state = "recording";
  }
  stop() {
    this.state = "inactive";
    this.onstop?.();
  }
}

function baseDeps(
  stream: FakeStream,
  recorder: FakeRecorder,
  networkCalls: unknown[],
): VoiceRecorderDeps {
  return {
    checkSupport: () => ({ supported: true }),
    getUserMedia: async () => stream as unknown as MediaStream,
    createRecorder: () => recorder as unknown as MediaRecorder,
    isTypeSupported: () => true,
    // AudioContext 를 못 여는 브라우저와 같은 경로 — VAD 없이 재탭으로만 멈춘다.
    createAudioContext: () => {
      throw new Error("no audio context");
    },
    createBlob: () => new Blob([new Uint8Array(2048)], { type: "audio/webm" }),
    fetch: (async (input: unknown) => {
      networkCalls.push(input);
      throw new Error("real network must not be used");
    }) as unknown as typeof fetch,
    now: () => 0,
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: () => 1,
    clearTimeout: () => {},
  };
}

async function settle() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

describe("createRoleplayVoiceDeps — adapter", () => {
  const { deps, takeAudio } = createRoleplayVoiceDeps(
    baseDeps(new FakeStream(), new FakeRecorder(), []),
  );

  test("captures the audio part and answers with a non-empty placeholder", async () => {
    const form = new FormData();
    form.append("audio", new Blob([new Uint8Array(10)], { type: "audio/webm" }), "r.webm");

    const response = await deps.fetch("/api/transcriptions", { method: "POST", body: form });

    expect(response.ok).toBe(true);
    expect(await response.json()).toEqual({ transcript: PLACEHOLDER_TRANSCRIPT });
    expect(PLACEHOLDER_TRANSCRIPT.trim().length).toBeGreaterThan(0);
    expect(takeAudio()?.size).toBe(10);
  });

  test("takeAudio hands the recording over once", async () => {
    const form = new FormData();
    form.append("audio", new Blob([new Uint8Array(5)]), "r.webm");
    await deps.fetch("/api/transcriptions", { method: "POST", body: form });

    expect(takeAudio()?.size).toBe(5);
    expect(takeAudio()).toBeNull();
  });

  test("throws on any other URL instead of letting it reach the network", async () => {
    await expect(deps.fetch("/api/analysis", { method: "POST" })).rejects.toThrow(
      "unexpected request",
    );
  });

  test("throws when the transcription request carries no audio", async () => {
    await expect(
      deps.fetch("/api/transcriptions", { method: "POST", body: new FormData() }),
    ).rejects.toThrow("no audio");
  });
});

describe("createRoleplayVoiceDeps — contract with the real VoiceRecorderController", () => {
  test("a finished recording reaches onTranscript once, with the audio, and no network call", async () => {
    const stream = new FakeStream();
    const recorder = new FakeRecorder();
    const networkCalls: unknown[] = [];
    const { deps, takeAudio } = createRoleplayVoiceDeps(
      baseDeps(stream, recorder, networkCalls),
    );

    const heard: Array<{ transcript: string; audioSize: number | undefined }> = [];
    const controller = new VoiceRecorderController(deps, (transcript) => {
      heard.push({ transcript, audioSize: takeAudio()?.size });
    });

    controller.dispatch({ type: "tap" }); // 권한 요청
    await settle();
    expect(controller.getSnapshot().machine.status).toBe("recording");

    controller.dispatch({ type: "tap" }); // 정지 → 전사(어댑터) → 확인
    await settle();

    expect(heard).toEqual([{ transcript: PLACEHOLDER_TRANSCRIPT, audioSize: 2048 }]);
    expect(networkCalls).toHaveLength(0);
    // 확인 단계를 지나면 컨트롤러는 idle 로 돌아가고 마이크를 놓는다.
    expect(controller.getSnapshot().machine.status).toBe("idle");
    expect(stream.stopped).toBeGreaterThan(0);
  });
});

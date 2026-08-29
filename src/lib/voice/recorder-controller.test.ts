import { describe, expect, test } from "bun:test";

import {
  MAX_DURATION_MS,
  VAD_INTERVAL_MS,
  VoiceRecorderController,
  type VoiceRecorderDeps,
} from "./recorder-controller";
import type { VoiceStatus } from "./recorder-machine";

// 이 파일이 겨냥하는 것은 자원 해제다. 마이크 트랙·AudioContext·타이머·AbortController가
// 정상 경로뿐 아니라 에러·취소·언마운트·장치 분리에서도 반드시 풀리는지 본다.

class FakeTrack {
  stopped = 0;
  stop() {
    this.stopped += 1;
  }
}

class FakeStream {
  readonly tracks = [new FakeTrack()];
  getTracks() {
    return this.tracks;
  }
  get released(): boolean {
    return this.tracks.every((track) => track.stopped > 0);
  }
}

class FakeRecorder {
  state: "inactive" | "recording" = "inactive";
  mimeType = "audio/webm;codecs=opus";
  startCalls = 0;
  stopCalls = 0;
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  /** start() 가 던지도록 만든다(권한 승인 직후 트랙이 끝난 경우 재현). */
  throwOnStart = false;

  start() {
    this.startCalls += 1;
    if (this.throwOnStart) {
      throw new Error("InvalidStateError");
    }
    this.state = "recording";
  }
  stop() {
    this.stopCalls += 1;
    this.state = "inactive";
    this.onstop?.();
  }
  /** 브라우저가 스스로 멈추는 경로: 장치 분리, 권한 회수. */
  endOnItsOwn() {
    this.state = "inactive";
    this.onstop?.();
  }
  raiseError() {
    this.state = "inactive";
    this.onerror?.();
  }
}

class FakeAudioContext {
  state = "running";
  closed = 0;
  /** 128은 무음(중앙값). 값을 올리면 발화로 읽힌다. */
  level = 128;
  createAnalyser() {
    return {
      fftSize: 2048,
      getByteTimeDomainData: (samples: Uint8Array) => samples.fill(this.level),
    };
  }
  createMediaStreamSource() {
    return { connect: () => {} };
  }
  resume() {
    return Promise.resolve();
  }
  close() {
    this.closed += 1;
    return Promise.resolve();
  }
}

type Harness = {
  deps: VoiceRecorderDeps;
  stream: FakeStream;
  recorder: FakeRecorder;
  audioContext: FakeAudioContext;
  transcripts: string[];
  /** 등록된 인터벌을 n번 실행한다. */
  tick: (times?: number) => void;
  advance: (ms: number) => void;
  pendingTimeouts: () => number;
  fetchCalls: () => number;
  lastSignal: () => AbortSignal | undefined;
  resolvePermission: () => Promise<void>;
  settle: () => Promise<void>;
};

type FetchOutcome =
  | { kind: "ok"; transcript: string }
  | { kind: "status"; status: number }
  | { kind: "reject" }
  | { kind: "hang" };

function harness(
  options: {
    supported?: boolean;
    permission?: "grant" | "deny";
    fetchOutcome?: FetchOutcome;
    throwOnCreate?: boolean;
    throwOnStart?: boolean;
    blobSize?: number;
  } = {},
): { controller: VoiceRecorderController; h: Harness } {
  const stream = new FakeStream();
  const recorder = new FakeRecorder();
  recorder.throwOnStart = options.throwOnStart ?? false;
  const audioContext = new FakeAudioContext();
  const transcripts: string[] = [];

  let clock = 0;
  const intervals = new Map<number, () => void>();
  const timeouts = new Map<number, () => void>();
  let nextTimerId = 1;
  let fetchCalls = 0;
  let lastSignal: AbortSignal | undefined;
  let releasePermission: (() => void) | undefined;

  const permissionPromise = new Promise<void>((resolve) => {
    releasePermission = resolve;
  });

  const deps: VoiceRecorderDeps = {
    checkSupport: () =>
      options.supported === false
        ? { supported: false, reason: "no_webm_opus" }
        : { supported: true },
    getUserMedia: async () => {
      await permissionPromise;
      if (options.permission === "deny") {
        throw new Error("NotAllowedError");
      }
      return stream as unknown as MediaStream;
    },
    createRecorder: () => {
      if (options.throwOnCreate) {
        throw new Error("NotSupportedError");
      }
      return recorder as unknown as MediaRecorder;
    },
    isTypeSupported: () => true,
    createAudioContext: () => audioContext as unknown as AudioContext,
    // FormData.append 이 진짜 Blob 을 요구하므로 실물을 만든다(크기만 조절한다).
    createBlob: () =>
      new Blob([new Uint8Array(options.blobSize ?? 2048)], {
        type: "audio/webm",
      }),
    fetch: (async (_input: unknown, init?: RequestInit) => {
      fetchCalls += 1;
      lastSignal = init?.signal ?? undefined;
      const outcome = options.fetchOutcome ?? {
        kind: "ok" as const,
        transcript: "안녕하세요",
      };
      if (outcome.kind === "hang") {
        return new Promise<Response>(() => {});
      }
      if (outcome.kind === "reject") {
        throw new Error("network down");
      }
      if (outcome.kind === "status") {
        return {
          ok: false,
          status: outcome.status,
          json: async () => ({}),
        } as Response;
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ transcript: outcome.transcript }),
      } as Response;
    }) as unknown as typeof fetch,
    now: () => clock,
    setInterval: (handler) => {
      const id = nextTimerId++;
      intervals.set(id, handler);
      return id;
    },
    clearInterval: (id) => {
      intervals.delete(id);
    },
    setTimeout: (handler) => {
      const id = nextTimerId++;
      timeouts.set(id, handler);
      return id;
    },
    clearTimeout: (id) => {
      timeouts.delete(id);
    },
  };

  const controller = new VoiceRecorderController(deps, (transcript) => {
    transcripts.push(transcript);
  });

  return {
    controller,
    h: {
      deps,
      stream,
      recorder,
      audioContext,
      transcripts,
      tick: (times = 1) => {
        for (let i = 0; i < times; i++) {
          for (const handler of [...intervals.values()]) {
            handler();
          }
        }
      },
      advance: (ms) => {
        clock += ms;
      },
      pendingTimeouts: () => timeouts.size,
      fetchCalls: () => fetchCalls,
      lastSignal: () => lastSignal,
      resolvePermission: async () => {
        releasePermission?.();
        await permissionPromise;
        await Promise.resolve();
        await Promise.resolve();
      },
      settle: async () => {
        for (let i = 0; i < 8; i++) {
          await Promise.resolve();
        }
      },
    },
  };
}

async function startRecording(options: Parameters<typeof harness>[0] = {}) {
  const { controller, h } = harness(options);
  controller.dispatch({ type: "tap" });
  await h.resolvePermission();
  return { controller, h };
}

describe("VoiceRecorderController — 정상 경로", () => {
  test("탭 → 권한 → 녹음 → 정지 → 전사 → 확인, 그리고 마이크를 놓는다", async () => {
    const { controller, h } = await startRecording();
    expect(controller.getSnapshot().machine.status).toBe("recording");
    expect(h.recorder.startCalls).toBe(1);

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(h.recorder.stopCalls).toBe(1);
    expect(h.transcripts).toEqual(["안녕하세요"]);
    // 확인 단계는 전사문을 넘긴 뒤 즉시 idle로 되돌아간다.
    expect(controller.getSnapshot().machine.status).toBe("idle");
    expect(h.stream.released).toBe(true);
    expect(h.audioContext.closed).toBe(1);
  });

  test("timeslice 없이 start() 한다 — WebM Duration이 패치되어야 백엔드가 받는다", async () => {
    const { h } = await startRecording();
    expect(h.recorder.startCalls).toBe(1);
  });
});

describe("VoiceRecorderController — 마이크 해제", () => {
  test("녹음 중 취소해도 트랙과 AudioContext를 놓는다", async () => {
    const { controller, h } = await startRecording();

    controller.dispatch({ type: "cancel" });

    expect(controller.getSnapshot().machine.status).toBe("idle");
    expect(h.stream.released).toBe(true);
    expect(h.audioContext.closed).toBe(1);
    expect(h.recorder.stopCalls).toBe(1);
  });

  test("녹음 중 언마운트해도 트랙을 놓는다", async () => {
    const { controller, h } = await startRecording();

    controller.dispose();

    expect(h.stream.released).toBe(true);
    expect(h.audioContext.closed).toBe(1);
  });

  test("언마운트 뒤 도착한 권한 응답도 스트림을 놓는다", async () => {
    const { controller, h } = harness();
    controller.dispatch({ type: "tap" });
    controller.dispose();

    await h.resolvePermission();

    expect(h.stream.released).toBe(true);
  });

  test("전사 실패로 끝나도 마이크는 이미 놓여 있다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "status", status: 503 },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(controller.getSnapshot().machine.status).toBe("error_transient");
    expect(h.stream.released).toBe(true);
  });
});

describe("VoiceRecorderController — 정지 조건 3가지", () => {
  test("재탭으로 멈춘다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });
    controller.dispatch({ type: "tap" });
    expect(h.recorder.stopCalls).toBe(1);
    expect(controller.getSnapshot().machine.stopReason).toBe("tap");
  });

  test("하드컷은 58초에 걸린다 — 백엔드의 60.0초 상한보다 앞", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });

    // 계속 말하는 중으로 둔다 — 그래야 VAD 무음 정지가 먼저 끼어들지 않고 하드컷을 본다.
    h.audioContext.level = 200;
    for (
      let elapsed = VAD_INTERVAL_MS;
      elapsed < MAX_DURATION_MS;
      elapsed += VAD_INTERVAL_MS
    ) {
      h.advance(VAD_INTERVAL_MS);
      h.tick();
    }
    expect(controller.getSnapshot().machine.status).toBe("recording");

    h.advance(VAD_INTERVAL_MS);
    h.tick();

    expect(controller.getSnapshot().machine.stopReason).toBe("max_duration");
    expect(MAX_DURATION_MS).toBeLessThan(60_000);
  });

  test("무음이 이어지면 VAD가 멈춘다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });

    // 가짜 analyser는 항상 무음(128 = 중앙값)을 돌려준다 — 발화가 한 번도 없는 경로다.
    for (let elapsed = 0; elapsed < 12_000; elapsed += VAD_INTERVAL_MS) {
      h.advance(VAD_INTERVAL_MS);
      h.tick();
      if (controller.getSnapshot().machine.status !== "recording") {
        break;
      }
    }

    expect(controller.getSnapshot().machine.stopReason).toBe("no_speech");
  });

  test("녹음이 끝나면 경과 타이머가 더 돌지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });
    h.advance(1_000);
    h.tick();
    const running = controller.getSnapshot().elapsedMs;
    expect(running).toBeGreaterThan(0);

    controller.dispatch({ type: "cancel" });
    h.advance(5_000);
    h.tick();

    expect(controller.getSnapshot().elapsedMs).toBe(running);
  });
});

describe("VoiceRecorderController — 막다른 길", () => {
  // 리뷰가 찾은 교착의 실물 재현.
  test("녹음 중 장치가 빠져도 갇히지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });

    h.recorder.endOnItsOwn();

    // 예전에는 recording에 머물러 있다가, 다음 탭이 stopping으로 가서 멈출 recorder가
    // 없어 "마무리 중..."에 영영 갇혔다 — 마이크를 쥔 채로.
    expect(controller.getSnapshot().machine.status).toBe("transcribing");
    expect(h.stream.released).toBe(true);
  });

  test("MediaRecorder 오류는 안내 가능한 상태로 빠진다", async () => {
    const { controller, h } = await startRecording();

    h.recorder.raiseError();

    expect(controller.getSnapshot().machine.status).toBe("error_recording");
    expect(h.stream.released).toBe(true);
  });

  test("start()가 던져도 화면이 죽지 않는다", async () => {
    const { controller, h } = await startRecording({ throwOnStart: true });

    expect(controller.getSnapshot().machine.status).toBe("error_recording");
    expect(h.stream.released).toBe(true);
  });

  // 처음 쓸 때는 unsupported 를 보냈는데, 그 이벤트는 requesting_permission 에서만 받는다.
  // 이미 recording 이라 무시되고 recorder 없이 갇혔다 — 위 교착과 같은 부류다.
  test("MediaRecorder 생성이 던져도 갇히지 않는다", async () => {
    const { controller, h } = await startRecording({ throwOnCreate: true });

    expect(controller.getSnapshot().machine.status).toBe("error_recording");
    expect(h.stream.released).toBe(true);
  });

  test("blob이 비어 있으면 stopping에 머물지 않는다", async () => {
    const { controller, h } = await startRecording({ blobSize: 0 });

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(controller.getSnapshot().machine.status).not.toBe("stopping");
    expect(h.stream.released).toBe(true);
  });
});

describe("VoiceRecorderController — 시작 전 실패", () => {
  test("미지원 브라우저에는 권한을 묻지 않는다", async () => {
    const { controller, h } = harness({ supported: false });

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(controller.getSnapshot().machine.status).toBe("unsupported");
    expect(controller.getSnapshot().machine.unsupportedReason).toBe(
      "no_webm_opus",
    );
    // 권한 요청도, 업로드도 일어나지 않아야 한다.
    expect(h.stream.released).toBe(false);
    expect(h.stream.tracks[0].stopped).toBe(0);
    expect(h.fetchCalls()).toBe(0);
  });

  test("권한 거부는 안내 상태로 끝난다", async () => {
    const { controller, h } = harness({ permission: "deny" });

    controller.dispatch({ type: "tap" });
    await h.resolvePermission();

    expect(controller.getSnapshot().machine.status).toBe("permission_denied");
    expect(h.fetchCalls()).toBe(0);
  });
});

describe("VoiceRecorderController — 전사 응답 분기", () => {
  const cases: Array<[number, VoiceStatus]> = [
    [422, "error_empty"],
    [400, "error_invalid_audio"],
    [413, "error_invalid_audio"],
    [429, "error_rate_limited"],
    [503, "error_transient"],
  ];

  for (const [status, expected] of cases) {
    test(`${status} → ${expected}`, async () => {
      const { controller, h } = await startRecording({
        fetchOutcome: { kind: "status", status },
      });

      controller.dispatch({ type: "tap" });
      await h.settle();

      expect(controller.getSnapshot().machine.status).toBe(expected);
    });
  }

  test("네트워크 예외는 재시도 가능한 실패다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "reject" },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(controller.getSnapshot().machine.status).toBe("error_transient");
  });

  test("빈 전사문을 200으로 받아도 실패로 본다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "ok", transcript: "   " },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(controller.getSnapshot().machine.status).toBe("error_empty");
    expect(h.transcripts).toEqual([]);
  });
});

describe("VoiceRecorderController — 진행 중 요청 정리", () => {
  test("전사 중 취소하면 요청을 abort하고 실패로 표시하지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();
    expect(controller.getSnapshot().machine.status).toBe("transcribing");

    controller.dispatch({ type: "cancel" });
    await h.settle();

    expect(h.lastSignal()?.aborted).toBe(true);
    expect(h.lastSignal()?.reason).toBe("cancel");
    // 사용자가 스스로 끊은 것이므로 에러 문구를 띄우지 않는다.
    expect(controller.getSnapshot().machine.status).toBe("idle");
  });

  test("전사 중 언마운트해도 요청을 abort한다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();
    controller.dispose();

    expect(h.lastSignal()?.aborted).toBe(true);
  });

  test("dispose 뒤에는 어떤 이벤트도 상태를 움직이지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });
    controller.dispose();
    const after = controller.getSnapshot().machine.status;

    controller.dispatch({ type: "tap" });
    h.tick();

    expect(controller.getSnapshot().machine.status).toBe(after);
  });

  test("전사가 끝나면 타임아웃 타이머를 남기지 않는다", async () => {
    const { controller, h } = await startRecording();

    controller.dispatch({ type: "tap" });
    await h.settle();

    expect(h.pendingTimeouts()).toBe(0);
  });

  test("재전송은 같은 녹음을 다시 올린다 — 다시 말하게 하지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "status", status: 503 },
    });

    controller.dispatch({ type: "tap" });
    await h.settle();
    expect(controller.getSnapshot().machine.status).toBe("error_transient");
    const before = h.fetchCalls();

    controller.dispatch({ type: "retry_transcribe" });
    await h.settle();

    expect(h.fetchCalls()).toBe(before + 1);
  });
});

describe("VoiceRecorderController — 구독", () => {
  test("상태가 바뀔 때만 스냅샷 신원이 바뀐다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });
    const seen: unknown[] = [];
    controller.subscribe(() => seen.push(controller.getSnapshot()));

    const before = controller.getSnapshot();
    h.tick(); // 경과 시간이 그대로면 알리지 않는다(clock을 움직이지 않았다)
    expect(controller.getSnapshot()).toBe(before);

    h.advance(500);
    h.tick();
    expect(controller.getSnapshot()).not.toBe(before);
    expect(seen.length).toBeGreaterThan(0);
  });

  test("구독을 해지하면 더 알리지 않는다", async () => {
    const { controller, h } = await startRecording({
      fetchOutcome: { kind: "hang" },
    });
    let calls = 0;
    const unsubscribe = controller.subscribe(() => {
      calls += 1;
    });
    h.advance(200);
    h.tick();
    const afterFirst = calls;
    expect(afterFirst).toBeGreaterThan(0);

    unsubscribe();
    h.advance(200);
    h.tick();

    expect(calls).toBe(afterFirst);
  });
});

// #143: 훅이 컨트롤러를 useState로 들고 있어서, 상태가 보존된 채 다시 마운트되면(StrictMode
// 이중 호출, Fast Refresh) 정리만 돌고 같은 인스턴스가 계속 렌더된다. 되돌릴 수 없는 정리는
// 그 시점부터 마이크 버튼을 영구히 먹통으로 만든다.
describe("VoiceRecorderController — 정리 후 재구독", () => {
  test("정리 뒤 다시 구독하면 탭이 다시 동작한다", async () => {
    const { controller, h } = harness();

    controller.dispose();
    let notified = 0;
    controller.subscribe(() => {
      notified += 1;
    });

    controller.dispatch({ type: "tap" });
    expect(controller.getSnapshot().machine.status).toBe(
      "requesting_permission",
    );

    await h.resolvePermission();

    expect(controller.getSnapshot().machine.status).toBe("recording");
    expect(h.recorder.startCalls).toBe(1);
    expect(notified).toBeGreaterThan(0);
  });

  test("녹음 중 정리되면 상태도 idle로 돌아간다 — 살아난 뒤 유령 녹음 화면을 남기지 않는다", async () => {
    const { controller, h } = await startRecording();
    h.advance(1_000);
    h.tick();
    expect(controller.getSnapshot().elapsedMs).toBeGreaterThan(0);

    controller.dispose();

    expect(controller.getSnapshot().machine.status).toBe("idle");
    expect(controller.getSnapshot().elapsedMs).toBe(0);
  });

  test("정리하고 다시 구독해도 마이크는 새로 잡는다 — 놓은 스트림을 재사용하지 않는다", async () => {
    const { controller, h } = await startRecording();
    controller.dispose();
    expect(h.stream.released).toBe(true);

    controller.subscribe(() => {});
    controller.dispatch({ type: "tap" });

    // 이전 스트림 참조가 남아 있었다면 권한 요청 없이 곧장 recording으로 갔을 것이다.
    expect(controller.getSnapshot().machine.status).toBe(
      "requesting_permission",
    );
  });
});

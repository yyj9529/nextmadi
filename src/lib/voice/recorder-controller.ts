import { RECORDING_MIME_TYPE, checkBrowserRecordingSupport } from "./capability";
import type { RecordingSupport } from "./capability";
import {
  INITIAL_VOICE_MACHINE,
  holdsMicrophone,
  reduceVoiceMachine,
  type VoiceEvent,
  type VoiceMachine,
} from "./recorder-machine";
import { SilenceMonitor, rmsFromTimeDomain } from "./silence-monitor";

// 음성 입력의 부수효과 오케스트레이션. (#36)
//
// recorder-machine.ts가 "무엇으로 바뀌는가"를, 이 파일이 "그때 무엇을 하는가"를 맡는다.
// React 밖의 평범한 클래스인 이유는 브라우저 API를 전부 주입받아 테스트하기 위해서다 —
// 마이크 트랙·AudioContext·타이머·AbortController의 해제는 이 기능에서 가장 위험한 부분인데,
// 훅 안에 있으면 React 렌더러 없이는 한 줄도 검증할 수 없다.
//
// **MediaRecorder.start()에 timeslice를 주지 않는다.** 조각으로 뱉으면 WebM 헤더의 Segment
// 크기와 Duration이 패치되지 않아 백엔드 WebmOpusInspector가 400으로 거부한다
// (2026-08-04 실측, WebmOpusInspectorRealRecordingTests 참고).

// 스펙 상한은 60초지만 58초에 끊는다. 이 타이머는 100ms 간격이고 stop() 이후 마지막 chunk가
// 합쳐지기까지도 시간이 걸려서, 60_000에 끊으면 실제 녹음이 60.0초를 넘길 수 있다. 백엔드
// WebmOpusInspector는 durationSeconds > 60.0을 400으로 거부하므로, 하드컷으로 끝난 녹음이
// "녹음이 올바르지 않아요"로 실패한다. 2초 여유를 둔다.
export const MAX_DURATION_MS = 58_000;
export const VAD_INTERVAL_MS = 100;
/** 백엔드 STT 타임아웃 30초(AI_PIPELINE.md) + 업로드/응답 여유. */
export const TRANSCRIBE_TIMEOUT_MS = 45_000;

export type TimerId = number;

/** 브라우저 경계. 테스트는 여기를 전부 가짜로 채운다. */
export type VoiceRecorderDeps = {
  checkSupport: () => RecordingSupport;
  getUserMedia: () => Promise<MediaStream>;
  createRecorder: (
    stream: MediaStream,
    options?: MediaRecorderOptions,
  ) => MediaRecorder;
  isTypeSupported: (mimeType: string) => boolean;
  createAudioContext: () => AudioContext;
  createBlob: (parts: Blob[], type: string) => Blob;
  fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
  now: () => number;
  setInterval: (handler: () => void, ms: number) => TimerId;
  clearInterval: (id: TimerId) => void;
  setTimeout: (handler: () => void, ms: number) => TimerId;
  clearTimeout: (id: TimerId) => void;
};

export function browserDeps(): VoiceRecorderDeps {
  return {
    checkSupport: checkBrowserRecordingSupport,
    getUserMedia: () => navigator.mediaDevices.getUserMedia({ audio: true }),
    createRecorder: (stream, options) => new MediaRecorder(stream, options),
    isTypeSupported: (mimeType) => MediaRecorder.isTypeSupported(mimeType),
    createAudioContext: () => new AudioContext(),
    createBlob: (parts, type) => new Blob(parts, { type }),
    fetch: (input, init) => fetch(input, init),
    now: () => Date.now(),
    setInterval: (handler, ms) => window.setInterval(handler, ms),
    clearInterval: (id) => window.clearInterval(id),
    setTimeout: (handler, ms) => window.setTimeout(handler, ms),
    clearTimeout: (id) => window.clearTimeout(id),
  };
}

export type VoiceSnapshot = {
  machine: VoiceMachine;
  /** 녹음 경과 시간(ms). 녹음 중에만 갱신된다. */
  elapsedMs: number;
};

export class VoiceRecorderController {
  private machine: VoiceMachine = INITIAL_VOICE_MACHINE;
  private elapsedMs = 0;
  private snapshot: VoiceSnapshot = {
    machine: INITIAL_VOICE_MACHINE,
    elapsedMs: 0,
  };
  private readonly listeners = new Set<() => void>();

  private stream: MediaStream | null = null;
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private blob: Blob | null = null;
  private audioContext: AudioContext | null = null;
  private vadTimer: TimerId | null = null;
  private abort: AbortController | null = null;
  /** getUserMedia 응답이 취소보다 늦게 와도 스트림을 흘리지 않기 위한 세대 카운터. */
  private permissionGeneration = 0;
  private disposed = false;

  constructor(
    private readonly deps: VoiceRecorderDeps,
    private onTranscript: (transcript: string) => void,
  ) {}

  /** prop이 매 렌더 새로 만들어져도 컨트롤러를 다시 만들지 않기 위해 콜백만 갈아끼운다. */
  setOnTranscript(next: (transcript: string) => void): void {
    this.onTranscript = next;
  }

  subscribe = (listener: () => void): (() => void) => {
    // 구독은 되살리기도 한다. dispose()는 "이 컨트롤러는 끝났다"가 아니라 "지금 쥔 자원을
    // 놓는다"는 뜻이다. 훅이 인스턴스를 useState로 들고 있어서(use-voice-recorder.ts) 상태가
    // 보존된 채 다시 마운트되면 — StrictMode 이중 호출, Fast Refresh — 정리만 돌고 같은
    // 인스턴스가 계속 렌더된다. 그때 disposed를 풀지 않으면 dispatch가 전부 버려져 마이크
    // 버튼이 영구히 먹통이 된다(#143).
    this.disposed = false;
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = (): VoiceSnapshot => this.snapshot;

  dispatch = (event: VoiceEvent): void => {
    if (this.disposed) {
      return;
    }
    const previous = this.machine;
    const next = reduceVoiceMachine(previous, event);
    if (next === previous) {
      return;
    }
    this.machine = next;
    this.publish();
    this.onStatusChanged(previous, next);
  };

  /**
   * 구독이 끊길 때의 정리. 어떤 경로로 끝나든 마이크와 진행 중 요청을 남기지 않는다.
   * 되돌릴 수 있다 — 다시 subscribe되면 살아난다(위 주석 참고).
   */
  dispose = (): void => {
    this.disposed = true;
    this.permissionGeneration += 1;
    this.releaseMicrophone();
    this.abort?.abort("cancel");
    this.abort = null;
    this.listeners.clear();
    // 자원을 다 놓고도 상태만 "녹음 중"으로 남으면, 되살아났을 때 recorder 없는 녹음 화면이
    // 뜨고 탭도 먹지 않는다. 놓은 것과 보이는 것을 맞춰 idle로 되돌린다.
    this.machine = INITIAL_VOICE_MACHINE;
    this.elapsedMs = 0;
    this.snapshot = { machine: INITIAL_VOICE_MACHINE, elapsedMs: 0 };
    this.chunks = [];
    this.blob = null;
  };

  private publish(): void {
    this.snapshot = { machine: this.machine, elapsedMs: this.elapsedMs };
    for (const listener of this.listeners) {
      listener();
    }
  }

  private setElapsed(ms: number): void {
    if (this.elapsedMs === ms) {
      return;
    }
    this.elapsedMs = ms;
    this.publish();
  }

  private onStatusChanged(previous: VoiceMachine, next: VoiceMachine): void {
    // 전사 구간을 벗어나면 진행 중 요청을 반드시 정리한다.
    if (previous.status === "transcribing" && next.status !== "transcribing") {
      this.abort?.abort("cancel");
      this.abort = null;
    }
    // 마이크 점유 구간을 벗어나면 예외 없이 놓는다. 녹음 표시등이 남는 것은
    // "이 앱이 몰래 듣고 있나"로 읽힌다.
    if (holdsMicrophone(previous.status) && !holdsMicrophone(next.status)) {
      this.releaseMicrophone();
    }

    switch (next.status) {
      case "requesting_permission":
        this.requestPermission();
        return;
      case "recording":
        this.startRecording();
        return;
      case "stopping":
        this.stopRecording();
        return;
      case "transcribing":
        this.transcribe();
        return;
      case "confirm":
        if (next.transcript) {
          this.onTranscript(next.transcript);
          this.dispatch({ type: "reset" });
        }
        return;
      default:
        return;
    }
  }

  private releaseMicrophone(): void {
    if (this.vadTimer !== null) {
      this.deps.clearInterval(this.vadTimer);
      this.vadTimer = null;
    }
    // 트랙만 놓고 recorder를 그대로 두면 활성 상태로 남는다 — 참조를 버리기 전에 멈춘다.
    const recorder = this.recorder;
    if (recorder && recorder.state !== "inactive") {
      try {
        recorder.stop();
      } catch {
        // 이미 정리된 recorder — 무시한다.
      }
    }
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    this.recorder = null;
    const audioContext = this.audioContext;
    this.audioContext = null;
    void audioContext?.close().catch(() => {});
  }

  private requestPermission(): void {
    if (this.stream) {
      return;
    }
    // 포맷 지원 확인이 권한 요청보다 먼저다 — 백엔드가 받지 못할 포맷으로만 녹음할 수 있는
    // 브라우저(Safari)에서 권한을 묻고 녹음까지 시킨 뒤 400으로 실패시키면, 사용자는
    // "다시 녹음해주세요"를 받고 다시 시도해도 같은 곳에 갇힌다(capability.ts).
    const support = this.deps.checkSupport();
    if (!support.supported) {
      this.dispatch({ type: "unsupported", reason: support.reason });
      return;
    }

    const generation = ++this.permissionGeneration;
    this.deps
      .getUserMedia()
      .then((stream) => {
        if (generation !== this.permissionGeneration || this.disposed) {
          // 그 사이 취소됐다면 잡자마자 놓는다 — 표시등이 남으면 안 된다.
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        this.stream = stream;
        this.dispatch({ type: "permission_granted" });
      })
      .catch(() => {
        if (generation === this.permissionGeneration && !this.disposed) {
          this.dispatch({ type: "permission_denied" });
        }
      });
  }

  private startRecording(): void {
    const stream = this.stream;
    if (!stream || this.recorder) {
      return;
    }

    this.setElapsed(0);

    // MediaRecorder 생성과 start()는 던질 수 있다. 권한 승인과 여기 사이에 트랙이 끝나면
    // start()가 InvalidStateError를 던진다. 훅 시절에는 효과에서 던진 예외가 React 트리를
    // 통째로 언마운트시켜 빈 화면이 됐다 — 이 플로우의 원칙이 "막다른 길 없음"인데 거기만 죽었다.
    let recorder: MediaRecorder;
    try {
      recorder = this.deps.createRecorder(
        stream,
        this.deps.isTypeSupported(RECORDING_MIME_TYPE)
          ? { mimeType: RECORDING_MIME_TYPE }
          : undefined,
      );
    } catch {
      // unsupported 를 보내지 않는다 — 그 이벤트는 requesting_permission 에서만 받으므로
      // 여기서 쓰면 무시되고 recorder 없이 recording 에 갇힌다(마이크를 쥔 채로).
      // 포맷 미지원은 이미 권한 요청 전 capability 게이트가 걸렀다. 여기 도달한 실패는
      // 런타임 실패이므로 녹음 실패로 다룬다.
      this.dispatch({ type: "recording_failed" });
      return;
    }
    this.recorder = recorder;
    this.chunks = [];
    this.blob = null;

    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    recorder.onstop = () => {
      this.blob = this.deps.createBlob(
        this.chunks,
        recorder.mimeType || RECORDING_MIME_TYPE,
      );
      this.chunks = [];
      this.dispatch({ type: "blob_ready" });
    };
    // 녹음 중 장치가 빠지거나 권한이 회수되면 여기로 온다. 핸들러가 없으면 상태머신은
    // recording에 머문 채 경과 시간만 돌다가 하드컷으로 stopping에 들어가 갇힌다.
    recorder.onerror = () => {
      this.dispatch({ type: "recording_failed" });
    };

    try {
      recorder.start(); // timeslice 금지 — 위 주석 참고
    } catch {
      this.dispatch({ type: "recording_failed" });
      return;
    }

    const startedAt = this.deps.now();
    const monitor = new SilenceMonitor();
    const probe = this.createSilenceProbe(stream);

    this.vadTimer = this.deps.setInterval(() => {
      const elapsed = this.deps.now() - startedAt;
      this.setElapsed(elapsed);

      if (elapsed >= MAX_DURATION_MS) {
        this.dispatch({ type: "stop", reason: "max_duration" });
        return;
      }

      const rms = probe?.();
      if (rms === undefined) {
        return;
      }
      const decision = monitor.sample(rms, elapsed);
      if (decision === "stop_silence") {
        this.dispatch({ type: "stop", reason: "silence" });
      } else if (decision === "stop_no_speech") {
        this.dispatch({ type: "stop", reason: "no_speech" });
      }
    }, VAD_INTERVAL_MS);
  }

  /** AudioContext를 못 열면 null을 준다 — VAD 없이 가고 재탭·하드컷은 그대로 동작한다. */
  private createSilenceProbe(stream: MediaStream): (() => number) | null {
    try {
      const audioContext = this.deps.createAudioContext();
      this.audioContext = audioContext;
      // suspended로 열리면 RMS가 계속 0이라 VAD가 "발화 없음"으로 오판해 녹음을 일찍 끊는다.
      // 탭이 사용자 제스처라 보통은 running이지만 iOS Safari에서 특히 취약하다.
      if (audioContext.state === "suspended") {
        void audioContext.resume().catch(() => {});
      }
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 2048;
      audioContext.createMediaStreamSource(stream).connect(analyser);
      const samples = new Uint8Array(analyser.fftSize);
      return () => {
        analyser.getByteTimeDomainData(samples);
        return rmsFromTimeDomain(samples);
      };
    } catch {
      return null;
    }
  }

  private stopRecording(): void {
    const recorder = this.recorder;
    if (recorder && recorder.state !== "inactive") {
      recorder.stop();
      return;
    }
    // recorder가 없거나(참조 소실) 이미 inactive인(스스로 멈춘) 비정상 경로. 여기서 아무것도
    // 하지 않으면 "마무리 중..."에 갇힌 채 마이크를 계속 쥔다 — stopping에서는 탭도 무시되므로
    // 새로고침 말고는 빠져나올 길이 없다.
    if (this.blob && this.blob.size > 0) {
      this.dispatch({ type: "blob_ready" });
    } else {
      this.dispatch({ type: "recording_failed" });
    }
  }

  private transcribe(): void {
    const blob = this.blob;
    if (!blob || blob.size === 0) {
      this.dispatch({ type: "transcribe_failed", kind: "empty" });
      return;
    }

    const controller = new AbortController();
    this.abort = controller;
    const timeout = this.deps.setTimeout(
      () => controller.abort(),
      TRANSCRIBE_TIMEOUT_MS,
    );

    const form = new FormData();
    form.append("audio", blob, "recording.webm");

    this.deps
      .fetch("/api/transcriptions", {
        method: "POST",
        body: form,
        credentials: "same-origin",
        signal: controller.signal,
      })
      .then(async (response) => {
        if (response.ok) {
          const body = (await response.json()) as { transcript?: string };
          this.dispatch({
            type: "transcribed",
            transcript: body.transcript ?? "",
          });
          return;
        }
        if (response.status === 422) {
          this.dispatch({ type: "transcribe_failed", kind: "empty" });
          return;
        }
        if (response.status === 400 || response.status === 413) {
          this.dispatch({ type: "transcribe_failed", kind: "invalid_audio" });
          return;
        }
        if (response.status === 429) {
          // 오늘 치를 다 썼다. 재시도를 권하면 같은 429가 반복될 뿐이다.
          this.dispatch({ type: "transcribe_failed", kind: "rate_limited" });
          return;
        }
        this.dispatch({ type: "transcribe_failed", kind: "transient" });
      })
      .catch(() => {
        // 사용자가 취소해서 끊긴 abort는 실패가 아니다 — 타임아웃 abort만 실패로 본다.
        if (controller.signal.reason === "cancel") {
          return;
        }
        this.dispatch({ type: "transcribe_failed", kind: "transient" });
      })
      .finally(() => {
        this.deps.clearTimeout(timeout);
        if (this.abort === controller) {
          this.abort = null;
        }
      });
  }
}

// 녹음 자동 종료 판정(VAD). (#36, s02.md "tap-to-toggle, VAD, 60s max")
//
// Web Audio의 RMS 샘플을 시간순으로 먹여주면 언제 멈춰야 하는지 알려준다. 오디오 API와
// 분리해 둔 이유는 임계값·대기시간 튜닝을 브라우저 없이 검증하기 위해서다.
//
// 파라미터 초기값은 실사용 데이터가 없는 상태의 출발점이다. 실제 사용자 녹음이 쌓이면
// 조정한다 — 특히 threshold는 기기 마이크 감도에 따라 흔들린다.

export type SilenceDecision = "continue" | "stop_silence" | "stop_no_speech";

export type SilenceMonitorOptions = {
  /** 이 RMS를 넘으면 발화로 본다. */
  threshold: number;
  /** 발화 후 이만큼 조용하면 종료. */
  silenceHoldMs: number;
  /** 녹음 시작 직후 이 구간에서는 종료 판정을 하지 않는다. */
  graceMs: number;
  /** 한 번도 발화가 없으면 이 시점에 스스로 종료. */
  noSpeechTimeoutMs: number;
};

export const SILENCE_DEFAULTS: SilenceMonitorOptions = {
  /** 이 RMS를 넘으면 발화로 본다. */
  threshold: 0.01,
  /** 발화 후 이만큼 조용하면 종료. */
  silenceHoldMs: 2_000,
  /** 녹음 시작 직후 이 구간에서는 종료 판정을 하지 않는다(말 시작 전 무음 보호). */
  graceMs: 1_500,
  /** 한 번도 발화가 없으면 이 시점에 스스로 종료 → 빈 전사 경로로 안내한다. */
  noSpeechTimeoutMs: 8_000,
};

export class SilenceMonitor {
  private readonly options: SilenceMonitorOptions;
  private speechDetected = false;
  private lastLoudMs = 0;

  constructor(options: Partial<SilenceMonitorOptions> = {}) {
    this.options = { ...SILENCE_DEFAULTS, ...options };
  }

  /** 발화가 한 번이라도 있었는지 — 빈 전사 안내 문구를 고를 때 쓴다. */
  get heardSpeech(): boolean {
    return this.speechDetected;
  }

  sample(rms: number, elapsedMs: number): SilenceDecision {
    if (rms > this.options.threshold) {
      this.speechDetected = true;
      this.lastLoudMs = elapsedMs;
      return "continue";
    }

    if (elapsedMs < this.options.graceMs) {
      return "continue";
    }

    if (!this.speechDetected) {
      return elapsedMs >= this.options.noSpeechTimeoutMs
        ? "stop_no_speech"
        : "continue";
    }

    return elapsedMs - this.lastLoudMs >= this.options.silenceHoldMs
      ? "stop_silence"
      : "continue";
  }
}

/** AnalyserNode 시간영역 데이터(0~255, 128이 무음)에서 RMS를 계산한다. */
export function rmsFromTimeDomain(samples: Uint8Array<ArrayBufferLike>): number {
  if (samples.length === 0) {
    return 0;
  }
  let sum = 0;
  for (const sample of samples) {
    const normalized = (sample - 128) / 128;
    sum += normalized * normalized;
  }
  return Math.sqrt(sum / samples.length);
}

import { describe, expect, test } from "bun:test";

import { SilenceMonitor, SILENCE_DEFAULTS } from "./silence-monitor";

const LOUD = 0.2;
const QUIET = 0.001;

describe("무음 감지(VAD)", () => {
  test("녹음 직후 grace 구간의 무음은 무시한다 — 말 시작 전에 끊기면 안 된다", () => {
    const monitor = new SilenceMonitor();

    for (let t = 0; t < SILENCE_DEFAULTS.graceMs; t += 100) {
      expect(monitor.sample(QUIET, t)).toBe("continue");
    }
  });

  test("말한 뒤 무음이 2초 이어지면 정지한다", () => {
    const monitor = new SilenceMonitor();

    expect(monitor.sample(LOUD, 2_000)).toBe("continue");
    expect(monitor.sample(QUIET, 3_000)).toBe("continue");
    expect(monitor.sample(QUIET, 3_900)).toBe("continue");
    expect(monitor.sample(QUIET, 4_000)).toBe("stop_silence");
  });

  test("중간에 다시 말하면 무음 타이머가 초기화된다", () => {
    const monitor = new SilenceMonitor();

    monitor.sample(LOUD, 2_000);
    expect(monitor.sample(QUIET, 3_500)).toBe("continue");
    expect(monitor.sample(LOUD, 3_600)).toBe("continue");
    // 3.6초에 다시 말했으므로 4초 시점은 무음 0.4초일 뿐이다.
    expect(monitor.sample(QUIET, 4_000)).toBe("continue");
    expect(monitor.sample(QUIET, 5_600)).toBe("stop_silence");
  });

  test("계속 말하는 동안에는 멈추지 않는다", () => {
    const monitor = new SilenceMonitor();

    for (let t = 0; t <= 30_000; t += 250) {
      expect(monitor.sample(LOUD, t)).toBe("continue");
    }
  });

  test("한 번도 말하지 않으면 8초에 스스로 멈춘다", () => {
    const monitor = new SilenceMonitor();

    expect(monitor.sample(QUIET, 7_900)).toBe("continue");
    expect(monitor.sample(QUIET, 8_000)).toBe("stop_no_speech");
  });

  test("grace 구간에 말한 것도 발화로 친다", () => {
    const monitor = new SilenceMonitor();

    monitor.sample(LOUD, 500);
    // 발화가 있었으므로 8초 무발화 컷이 아니라 무음 컷으로 끝나야 한다.
    expect(monitor.sample(QUIET, 2_400)).toBe("continue");
    expect(monitor.sample(QUIET, 2_500)).toBe("stop_silence");
  });

  test("임계값과 대기시간을 조정할 수 있다", () => {
    const monitor = new SilenceMonitor({ threshold: 0.5, silenceHoldMs: 500, graceMs: 0 });

    expect(monitor.sample(LOUD, 0)).toBe("continue"); // 0.2 < 0.5 → 무음 취급
    expect(monitor.sample(0.6, 100)).toBe("continue");
    expect(monitor.sample(LOUD, 700)).toBe("stop_silence");
  });
});

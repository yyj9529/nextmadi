import { describe, expect, test } from "bun:test";

import {
  INITIAL_VOICE_MACHINE,
  holdsMicrophone,
  reduceVoiceMachine as reduce,
  type VoiceMachine,
} from "./recorder-machine";

function at(status: VoiceMachine["status"]): VoiceMachine {
  return { ...INITIAL_VOICE_MACHINE, status };
}

describe("녹음 상태머신 — 정상 경로", () => {
  test("탭 → 권한 요청 → 녹음 → 정지 → 전사 → 확인", () => {
    let machine = reduce(INITIAL_VOICE_MACHINE, { type: "tap" });
    expect(machine.status).toBe("requesting_permission");

    machine = reduce(machine, { type: "permission_granted" });
    expect(machine.status).toBe("recording");

    machine = reduce(machine, { type: "stop", reason: "tap" });
    expect(machine.status).toBe("stopping");
    expect(machine.stopReason).toBe("tap");

    machine = reduce(machine, { type: "blob_ready" });
    expect(machine.status).toBe("transcribing");

    machine = reduce(machine, { type: "transcribed", transcript: "  안녕하세요  " });
    expect(machine.status).toBe("confirm");
    expect(machine.transcript).toBe("안녕하세요");
  });

  test("녹음 중 재탭은 정지다(사유 tap)", () => {
    const machine = reduce(at("recording"), { type: "tap" });

    expect(machine.status).toBe("stopping");
    expect(machine.stopReason).toBe("tap");
  });

  test("VAD 무음과 60초 하드컷도 같은 정지 경로를 탄다", () => {
    for (const reason of ["silence", "max_duration"] as const) {
      const machine = reduce(at("recording"), { type: "stop", reason });
      expect(machine.status).toBe("stopping");
      expect(machine.stopReason).toBe(reason);
    }
  });

  test("확인 단계에서 전사문을 넘기면 idle로 돌아간다", () => {
    const confirmed = reduce(at("transcribing"), {
      type: "transcribed",
      transcript: "테스트 문장",
    });

    expect(reduce(confirmed, { type: "reset" }).status).toBe("idle");
    expect(reduce(confirmed, { type: "reset" }).transcript).toBeNull();
  });
});

describe("녹음 상태머신 — 실패 경로", () => {
  test("마이크 권한 거부는 종료 상태다", () => {
    const machine = reduce(at("requesting_permission"), {
      type: "permission_denied",
    });

    expect(machine.status).toBe("permission_denied");
    // 거부 상태에서 또 탭해도 권한 요청을 반복하지 않는다 — 명시적 재시도만 받는다.
    expect(reduce(machine, { type: "tap" }).status).toBe("permission_denied");
  });

  test("미지원 브라우저도 종료 상태다", () => {
    const machine = reduce(at("requesting_permission"), { type: "unsupported" });

    expect(machine.status).toBe("unsupported");
    expect(reduce(machine, { type: "tap" }).status).toBe("unsupported");
  });

  test("미지원 사유를 실어 안내 문구를 가른다", () => {
    // Safari: MediaRecorder는 있지만 WebM/Opus를 못 만든다 — 백엔드가 받지 못하는 포맷이라
    // 녹음을 시작하기 전에 막는다.
    const safari = reduce(at("requesting_permission"), {
      type: "unsupported",
      reason: "no_webm_opus",
    });
    expect(safari.unsupportedReason).toBe("no_webm_opus");

    // 사유가 없으면 녹음기 자체가 없는 것으로 본다.
    expect(
      reduce(at("requesting_permission"), { type: "unsupported" })
        .unsupportedReason,
    ).toBe("no_recorder");

    // 재녹음 시도는 사유를 지우고 다시 판정한다.
    expect(reduce(safari, { type: "retry" }).unsupportedReason).toBeNull();
  });

  test("빈 전사는 error_empty로 간다 — 공백만 있어도 마찬가지", () => {
    expect(
      reduce(at("transcribing"), { type: "transcribed", transcript: "   " })
        .status,
    ).toBe("error_empty");
    expect(
      reduce(at("transcribing"), { type: "transcribe_failed", kind: "empty" })
        .status,
    ).toBe("error_empty");
  });

  test("전사 실패 종류별로 상태가 갈린다", () => {
    expect(
      reduce(at("transcribing"), {
        type: "transcribe_failed",
        kind: "transient",
      }).status,
    ).toBe("error_transient");
    expect(
      reduce(at("transcribing"), {
        type: "transcribe_failed",
        kind: "invalid_audio",
      }).status,
    ).toBe("error_invalid_audio");
  });

  test("전사 중 취소는 idle로 되돌린다", () => {
    expect(reduce(at("transcribing"), { type: "cancel" }).status).toBe("idle");
  });

  test("일시적 실패는 같은 녹음을 재전송할 수 있다", () => {
    expect(
      reduce(at("error_transient"), { type: "retry_transcribe" }).status,
    ).toBe("transcribing");
    // 오디오 자체가 잘못됐으면 재전송은 의미가 없다 — 다시 녹음해야 한다.
    expect(
      reduce(at("error_invalid_audio"), { type: "retry_transcribe" }).status,
    ).toBe("error_invalid_audio");
  });

  test("모든 실패 상태에서 재녹음이 가능하다", () => {
    const failures = [
      "permission_denied",
      "unsupported",
      "error_empty",
      "error_transient",
      "error_invalid_audio",
    ] as const;

    for (const status of failures) {
      expect(reduce(at(status), { type: "retry" }).status).toBe(
        "requesting_permission",
      );
    }
  });

  test("어긋난 이벤트는 상태를 바꾸지 않는다", () => {
    const recording = at("recording");

    expect(reduce(recording, { type: "blob_ready" })).toBe(recording);
    expect(reduce(recording, { type: "permission_granted" })).toBe(recording);
    const idle = at("idle");
    expect(reduce(idle, { type: "stop", reason: "tap" })).toBe(idle);
  });
});

describe("마이크 점유", () => {
  test("녹음/정지 중에만 마이크를 잡는다", () => {
    expect(holdsMicrophone("recording")).toBe(true);
    expect(holdsMicrophone("stopping")).toBe(true);

    for (const status of [
      "idle",
      "requesting_permission",
      "transcribing",
      "confirm",
      "permission_denied",
      "unsupported",
      "error_empty",
      "error_transient",
      "error_invalid_audio",
    ] as const) {
      expect(holdsMicrophone(status)).toBe(false);
    }
  });

  test("녹음에서 벗어나는 모든 경로가 마이크를 놓는다 — 에러와 취소 포함", () => {
    const exits: Array<[VoiceMachine, Parameters<typeof reduce>[1]]> = [
      [at("recording"), { type: "stop", reason: "tap" }],
      [at("stopping"), { type: "blob_ready" }],
      [at("recording"), { type: "cancel" }],
      [at("stopping"), { type: "cancel" }],
    ];

    for (const [before, event] of exits) {
      const after = reduce(before, event);
      if (holdsMicrophone(before.status) && !holdsMicrophone(after.status)) {
        continue;
      }
      // stopping은 아직 마지막 chunk를 기다리므로 마이크를 유지한다.
      expect(after.status).toBe("stopping");
    }
  });
});

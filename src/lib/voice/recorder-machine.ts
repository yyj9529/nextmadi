// S02/S04 음성 입력 상태머신. (#36)
//
// 브라우저 API(MediaRecorder, getUserMedia, AudioContext)와 분리된 순수 함수다 — 갈림길이
// 많은 쪽(권한 거부 / 무음 / 60초 컷 / 빈 전사 / 타임아웃 / 취소)을 브라우저 없이 검증하기
// 위해서다. 실제 API 배선은 use-voice-recorder.ts가 맡는다.
//
// 흐름: idle → 권한 → 녹음 → 정지 → 전사 → 확인. 확인 단계에서 전사문을 화면에 넘기면
// (S02는 입력창, S04는 S05a 모달) 이후 분석 요청은 기존 텍스트 경로를 그대로 탄다.

export type VoiceStatus =
  | "idle"
  | "requesting_permission"
  | "recording"
  | "stopping"
  | "transcribing"
  | "confirm"
  | "permission_denied"
  | "unsupported"
  | "error_empty"
  | "error_transient"
  | "error_invalid_audio";

/** 녹음이 왜 멈췄는지 — 안내 문구가 갈린다. */
export type StopReason = "tap" | "silence" | "max_duration" | "no_speech";

export type TranscribeFailureKind = "empty" | "transient" | "invalid_audio";

/** 왜 녹음을 시작조차 못 하는지 — 안내 문구가 갈린다(capability.ts). */
export type UnsupportedReason = "no_recorder" | "no_webm_opus";

export type VoiceMachine = {
  status: VoiceStatus;
  stopReason: StopReason | null;
  transcript: string | null;
  unsupportedReason: UnsupportedReason | null;
};

export type VoiceEvent =
  | { type: "tap" }
  | { type: "permission_granted" }
  | { type: "permission_denied" }
  | { type: "unsupported"; reason?: UnsupportedReason }
  | { type: "stop"; reason: StopReason }
  | { type: "blob_ready" }
  | { type: "transcribed"; transcript: string }
  | { type: "transcribe_failed"; kind: TranscribeFailureKind }
  | { type: "cancel" }
  /** 같은 녹음을 다시 전송한다(일시적 실패 전용). */
  | { type: "retry_transcribe" }
  /** 처음부터 다시 녹음한다. */
  | { type: "retry" }
  | { type: "reset" };

export const INITIAL_VOICE_MACHINE: VoiceMachine = {
  status: "idle",
  stopReason: null,
  transcript: null,
  unsupportedReason: null,
};

const FAILURE_STATUSES: readonly VoiceStatus[] = [
  "permission_denied",
  "unsupported",
  "error_empty",
  "error_transient",
  "error_invalid_audio",
];

/**
 * 이 상태에서 마이크 스트림을 잡고 있는가.
 *
 * 훅은 이 값이 true → false로 바뀌는 순간 트랙을 반드시 stop()한다. 녹음 표시등이 남는 것은
 * "이 앱이 몰래 듣고 있나" 로 읽히므로 어떤 경로로 끝나든(에러·취소·언마운트) 예외가 없다.
 */
export function holdsMicrophone(status: VoiceStatus): boolean {
  return status === "recording" || status === "stopping";
}

export function isFailureStatus(status: VoiceStatus): boolean {
  return FAILURE_STATUSES.includes(status);
}

export function reduceVoiceMachine(
  machine: VoiceMachine,
  event: VoiceEvent,
): VoiceMachine {
  switch (event.type) {
    case "tap": {
      if (machine.status === "idle") {
        return {
          status: "requesting_permission",
          stopReason: null,
          transcript: null,
          unsupportedReason: null,
        };
      }
      if (machine.status === "recording") {
        // 녹음 중 재탭 = 정지(tap-to-toggle).
        return { ...machine, status: "stopping", stopReason: "tap" };
      }
      return machine;
    }

    case "permission_granted":
      return machine.status === "requesting_permission"
        ? { ...machine, status: "recording", stopReason: null }
        : machine;

    case "permission_denied":
      return machine.status === "requesting_permission"
        ? { ...machine, status: "permission_denied" }
        : machine;

    case "unsupported":
      return machine.status === "requesting_permission"
        ? {
            ...machine,
            status: "unsupported",
            unsupportedReason: event.reason ?? "no_recorder",
          }
        : machine;

    case "stop":
      return machine.status === "recording"
        ? { ...machine, status: "stopping", stopReason: event.reason }
        : machine;

    case "blob_ready":
      return machine.status === "stopping"
        ? { ...machine, status: "transcribing" }
        : machine;

    case "transcribed": {
      if (machine.status !== "transcribing") {
        return machine;
      }
      const transcript = event.transcript.trim();
      // 백엔드는 빈 전사를 400으로 막지만, 공백만 돌아오는 경우까지 여기서 한 번 더 막는다.
      if (transcript.length === 0) {
        return { ...machine, status: "error_empty", transcript: null };
      }
      return { ...machine, status: "confirm", transcript };
    }

    case "transcribe_failed": {
      if (machine.status !== "transcribing") {
        return machine;
      }
      const next: Record<TranscribeFailureKind, VoiceStatus> = {
        empty: "error_empty",
        transient: "error_transient",
        invalid_audio: "error_invalid_audio",
      };
      return { ...machine, status: next[event.kind], transcript: null };
    }

    case "cancel":
      return machine.status === "recording" ||
        machine.status === "stopping" ||
        machine.status === "transcribing"
        ? INITIAL_VOICE_MACHINE
        : machine;

    case "retry_transcribe":
      // 오디오 자체가 잘못된 경우(invalid_audio)는 재전송해도 같은 400이다 — 재녹음만 받는다.
      return machine.status === "error_transient"
        ? { ...machine, status: "transcribing" }
        : machine;

    case "retry":
      return isFailureStatus(machine.status)
        ? {
            status: "requesting_permission",
            stopReason: null,
            transcript: null,
            unsupportedReason: null,
          }
        : machine;

    case "reset":
      return machine.status === "idle" ? machine : INITIAL_VOICE_MACHINE;

    default:
      return machine;
  }
}

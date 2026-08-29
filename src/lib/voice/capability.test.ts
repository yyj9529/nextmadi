import { describe, expect, test } from "bun:test";

import { RECORDING_MIME_TYPE, checkRecordingSupport } from "./capability";

const chrome = {
  hasMediaDevices: true,
  hasMediaRecorder: true,
  isTypeSupported: (mimeType: string) => mimeType === RECORDING_MIME_TYPE,
};

describe("녹음 지원 판정", () => {
  test("WebM/Opus를 만들 수 있으면 지원", () => {
    expect(checkRecordingSupport(chrome)).toEqual({ supported: true });
  });

  test("Safari처럼 MediaRecorder는 있지만 WebM을 못 만들면 미지원", () => {
    // Safari는 audio/mp4만 지원한다. 그대로 올리면 백엔드가 400을 던지고,
    // 사용자는 다시 녹음해도 같은 결과를 받는 막다른 길에 갇힌다.
    const safari = {
      ...chrome,
      isTypeSupported: (mimeType: string) => mimeType === "audio/mp4",
    };

    expect(checkRecordingSupport(safari)).toEqual({
      supported: false,
      reason: "no_webm_opus",
    });
  });

  test("MediaRecorder 자체가 없으면 미지원", () => {
    expect(
      checkRecordingSupport({ ...chrome, hasMediaRecorder: false }),
    ).toEqual({ supported: false, reason: "no_recorder" });
  });

  test("getUserMedia가 없으면(비보안 컨텍스트 등) 미지원", () => {
    expect(
      checkRecordingSupport({ ...chrome, hasMediaDevices: false }),
    ).toEqual({ supported: false, reason: "no_recorder" });
  });

  test("아무 포맷도 지원하지 않으면 미지원", () => {
    expect(
      checkRecordingSupport({ ...chrome, isTypeSupported: () => false }),
    ).toEqual({ supported: false, reason: "no_webm_opus" });
  });
});

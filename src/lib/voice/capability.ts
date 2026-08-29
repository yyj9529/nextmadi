// 브라우저가 우리가 요구하는 녹음 포맷을 낼 수 있는지 판정한다. (#36)
//
// 백엔드 `POST /transcriptions`는 WebM/Opus만 받는다(WebmOpusInspector, openapi). Safari는
// MediaRecorder를 갖고 있지만 WebM을 만들지 못하고 mp4/AAC로 녹음한다 — 그대로 올리면
// 백엔드가 400을 던지고 사용자는 "다시 녹음해주세요"를 받는다. 다시 녹음해도 같은 결과라
// 빠져나갈 수 없는 루프가 된다. 그래서 **녹음을 시작하기 전에** 포맷 지원 여부를 확인하고,
// 못 만드는 브라우저에서는 마이크 권한조차 묻지 않고 텍스트 입력으로 안내한다.
//
// mp4/AAC 수용은 백엔드 계약 변경이라 별도 티켓이다 — 그때 이 게이트를 넓힌다.

export const RECORDING_MIME_TYPE = "audio/webm;codecs=opus";

export type RecordingEnvironment = {
  hasMediaDevices: boolean;
  hasMediaRecorder: boolean;
  isTypeSupported: (mimeType: string) => boolean;
};

export type RecordingSupport =
  | { supported: true }
  | { supported: false; reason: "no_recorder" | "no_webm_opus" };

export function checkRecordingSupport(
  env: RecordingEnvironment,
): RecordingSupport {
  if (!env.hasMediaDevices || !env.hasMediaRecorder) {
    return { supported: false, reason: "no_recorder" };
  }
  if (!env.isTypeSupported(RECORDING_MIME_TYPE)) {
    return { supported: false, reason: "no_webm_opus" };
  }
  return { supported: true };
}

/** 실행 중인 브라우저에서 위 판정을 수행한다. 서버에서는 항상 미지원으로 본다. */
export function checkBrowserRecordingSupport(): RecordingSupport {
  if (typeof window === "undefined" || typeof MediaRecorder === "undefined") {
    return { supported: false, reason: "no_recorder" };
  }
  return checkRecordingSupport({
    hasMediaDevices: Boolean(navigator.mediaDevices?.getUserMedia),
    hasMediaRecorder: true,
    isTypeSupported: (mimeType) => MediaRecorder.isTypeSupported(mimeType),
  });
}

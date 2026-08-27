"use client";

import { MicIcon } from "@/components/app/icons";
import type { VoiceRecorder } from "@/lib/voice/use-voice-recorder";

// S02(체험)·S04(홈)가 공유하는 음성 입력 표시 컴포넌트. (#36)
//
// 상태와 부수효과는 useVoiceRecorder가 갖고, 여기서는 상태에 맞는 버튼/문구만 그린다.
// 부모가 훅을 소유하는 이유는 전사 확정 후 목적지가 화면마다 다르기 때문이다
// (S02는 입력창 채우기, S04는 S05a 모달 열기).

type VoiceInputProps = {
  recorder: VoiceRecorder;
  /** hero: S04 홈의 큰 마이크 / compact: S02 입력창 아래 작은 마이크. */
  variant?: "hero" | "compact";
  /** 녹음이 불가능한 상태에서 텍스트 입력으로 유도하는 CTA. */
  onUseText?: () => void;
  /** 다른 작업이 진행 중이라 마이크를 막아야 할 때(예: 분석 요청 중). */
  disabled?: boolean;
};

function formatElapsed(elapsedMs: number): string {
  const totalSeconds = Math.floor(elapsedMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

export function VoiceInput({
  recorder,
  variant = "compact",
  onUseText,
  disabled = false,
}: VoiceInputProps) {
  const { status, elapsedMs } = recorder;
  const recording = status === "recording" || status === "stopping";
  const busy = status === "requesting_permission" || status === "transcribing";

  const caption = (() => {
    switch (status) {
      case "requesting_permission":
        return "마이크 준비 중...";
      case "recording":
        return `듣고 있어요 · ${formatElapsed(elapsedMs)}`;
      case "stopping":
        return "마무리 중...";
      case "transcribing":
        return "변환 중...";
      default:
        return variant === "hero"
          ? "탭하고 한국어로 말해보세요"
          : "또는 마이크로 말하기";
    }
  })();

  // 안내가 필요한 상태 — 어떻게 빠져나가는지까지 함께 보여준다.
  const notice = (() => {
    switch (status) {
      case "permission_denied":
        return {
          message: "마이크 권한이 없어요. 브라우저 설정에서 허용하거나 텍스트로 입력해주세요.",
          retryLabel: "다시 시도",
          onRetry: recorder.retry,
        };
      case "unsupported":
        // 재시도 CTA를 주지 않는다 — 브라우저가 못 하는 일이라 다시 눌러도 같은 결과다.
        // 유일한 출구는 텍스트 입력이므로 그쪽만 가리킨다.
        return {
          message:
            recorder.machine.unsupportedReason === "no_webm_opus"
              ? "이 브라우저는 음성 녹음을 지원하지 않아요. 텍스트로 입력해주세요."
              : "이 기기에서는 녹음할 수 없어요. 텍스트로 입력해주세요.",
          retryLabel: null,
          onRetry: null,
        };
      case "error_empty":
        return {
          message: "말소리를 알아듣지 못했어요. 다시 말해볼까요?",
          retryLabel: "다시 녹음",
          onRetry: recorder.retry,
        };
      case "error_transient":
        return {
          message: "변환에 실패했어요. 다시 시도해주세요.",
          retryLabel: "다시 시도",
          // 녹음은 멀쩡하다 — 다시 말하게 하지 않고 같은 녹음을 재전송한다.
          onRetry: recorder.retryTranscribe,
        };
      case "error_invalid_audio":
        return {
          message: "녹음이 올바르지 않아요. 다시 녹음해주세요.",
          retryLabel: "다시 녹음",
          onRetry: recorder.retry,
        };
      case "error_recording":
        // 전사가 아니라 녹음이 끊긴 경우다(장치 분리, 권한 회수, MediaRecorder 오류).
        // 재전송할 녹음이 없으므로 "다시 시도"가 아니라 처음부터 다시 녹음시킨다.
        return {
          message: "녹음이 중단됐어요. 마이크를 확인하고 다시 녹음해주세요.",
          retryLabel: "다시 녹음",
          onRetry: recorder.retry,
        };
      case "error_rate_limited":
        return {
          // 재시도 CTA를 주지 않는다 — 오늘은 다시 눌러도 같은 결과다. 텍스트 입력만 남긴다.
          message: "오늘 쓸 수 있는 음성 입력을 다 썼어요. 텍스트로 입력해보세요.",
          retryLabel: null,
          onRetry: null,
        };
      default:
        return null;
    }
  })();

  return (
    <div className={`voice-input voice-input-${variant}`}>
      <button
        className={`${variant === "hero" ? "mic-button" : "try-mic-button"}${
          recording ? " is-recording" : ""
        }`}
        type="button"
        aria-label={recording ? "녹음 멈추기" : "음성으로 말하기"}
        aria-pressed={recording}
        onClick={recorder.tap}
        // 녹음 중에는 disabled prop이 켜져도 정지는 막지 않는다 — 마이크를 열어둔 채
        // 끄지 못하는 상태를 만들지 않기 위해서다.
        disabled={busy || (disabled && !recording)}
      >
        <MicIcon size={variant === "hero" ? 32 : 26} />
      </button>

      <p className="voice-input-caption" aria-live="polite">
        {caption}
      </p>

      {notice ? (
        <div className="voice-input-notice" role="status">
          <p className="voice-input-notice-message">{notice.message}</p>
          <div className="voice-input-notice-actions">
            {notice.onRetry ? (
              <button
                className="voice-input-retry"
                type="button"
                onClick={notice.onRetry}
              >
                {notice.retryLabel}
              </button>
            ) : null}
            {onUseText ? (
              <button
                className="voice-input-use-text"
                type="button"
                onClick={onUseText}
              >
                텍스트로 입력하기
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

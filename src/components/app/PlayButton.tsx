"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { PauseIcon, PlayIcon } from "@/components/app/icons";

type PlayButtonProps = {
  /** 재생할 영어 문장 — TTS 텍스트이자 aria 라벨에 쓰인다. */
  label: string;
};

type PlayStatus = "idle" | "loading" | "playing" | "paused" | "error";

// S07 카드 TTS 재생 버튼. (#40)
//
// 탭하면 POST /api/tts/playback(BFF)로 현재 코치 voice의 서명 URL을 받아 HTML5 Audio로 재생한다.
// 같은 카드를 다시 재생하면 BFF가 다시 호출되고 백엔드 tts_audio_cache가 hit를 돌려준다(추가 과금
// 없음, US2-AC2). 재생 중 탭하면 일시정지하고, 일시정지 상태에서 탭하면 이어서 재생한다(US2 / UI
// states). 실패(타임아웃/오류/백엔드 미구현)하면 토스트를 띄우고 카드는 저장 등 다른 동작에 계속
// 쓸 수 있다(US2-AC3).
export function PlayButton({ label }: PlayButtonProps) {
  const [status, setStatus] = useState<PlayStatus>("idle");
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // 언마운트 시 재생 중인 오디오를 정리한다.
  useEffect(() => {
    return () => {
      const audio = audioRef.current;
      if (audio) {
        audio.pause();
        audio.src = "";
        audioRef.current = null;
      }
    };
  }, []);

  const loadAndPlay = useCallback(async () => {
    setStatus("loading");
    try {
      const response = await fetch("/api/tts/playback", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ text: label }),
      });
      if (!response.ok) {
        setStatus("error");
        return;
      }
      const data = (await response.json()) as { audio_url?: string };
      if (!data.audio_url) {
        setStatus("error");
        return;
      }

      const audio = new Audio(data.audio_url);
      audioRef.current = audio;
      audio.addEventListener("ended", () => setStatus("idle"));
      audio.addEventListener("error", () => setStatus("error"));
      await audio.play();
      setStatus("playing");
    } catch {
      setStatus("error");
    }
  }, [label]);

  const handleClick = useCallback(() => {
    const audio = audioRef.current;

    if (status === "playing" && audio) {
      audio.pause();
      setStatus("paused");
      return;
    }
    if (status === "paused" && audio) {
      void audio.play().then(
        () => setStatus("playing"),
        () => setStatus("error"),
      );
      return;
    }
    if (status === "loading") {
      return;
    }
    // idle / error / 재생 종료 → 새로 받아 재생.
    void loadAndPlay();
  }, [status, loadAndPlay]);

  const isPlaying = status === "playing";

  return (
    <span className="play-button-wrap">
      <button
        className={`play-button${isPlaying ? " is-playing" : ""}`}
        type="button"
        aria-label={isPlaying ? `${label} 일시정지` : `${label} 재생`}
        aria-busy={status === "loading"}
        onClick={handleClick}
      >
        {isPlaying ? <PauseIcon /> : <PlayIcon />}
      </button>
      {status === "error" ? (
        <span className="tts-error-toast" role="alert">
          음성 재생 실패, 다시 시도해주세요
        </span>
      ) : null}
    </span>
  );
}

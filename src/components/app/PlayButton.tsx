"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { PauseIcon, PlayIcon } from "@/components/app/icons";

type PlayButtonProps = {
  /** English sentence to synthesize and use in the aria label. */
  label: string;
};

type PlayStatus = "idle" | "loading" | "playing" | "paused" | "error";

// S07 card TTS playback button (#40).
//
// The BFF returns a presigned audio URL from the backend TTS cache/synthesis endpoint. Replaying
// the same text can be a backend cache hit, and playback failures are contained to the button so
// the card remains usable.
export function PlayButton({ label }: PlayButtonProps) {
  const [status, setStatus] = useState<PlayStatus>("idle");
  const audioRef = useRef<HTMLAudioElement | null>(null);

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
          음성 재생 실패, 다시 시도해 주세요
        </span>
      ) : null}
    </span>
  );
}

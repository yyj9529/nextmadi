"use client";

import { useEffect, useRef, useState } from "react";

import { PlayIcon } from "@/components/app/icons";

type PlayButtonProps = {
  label: string;
};

// TTS 재생 버튼 — 실제 구현은 POST /tts/playback { text, voice_id } 후
// 반환된 audio_url 재생. 목 패스에서는 1.2초간 재생 중 상태만 보여준다.
export function PlayButton({ label }: PlayButtonProps) {
  const [playing, setPlaying] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const handlePlay = () => {
    setPlaying(true);
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    timerRef.current = window.setTimeout(() => setPlaying(false), 1200);
  };

  return (
    <button
      className={`play-button${playing ? " is-playing" : ""}`}
      type="button"
      aria-label={`${label} 재생`}
      onClick={handlePlay}
    >
      <PlayIcon />
    </button>
  );
}

"use client";

import { useState } from "react";

import { TextInputSheet } from "@/components/app/AnalysisModals";
import { VoiceInput } from "@/components/app/VoiceInput";
import { useVoiceRecorder } from "@/lib/voice/use-voice-recorder";

// S04 새 분석 시작 카드.
//
// 음성(#36): 녹음 → POST /api/transcriptions → 전사문을 S05a 모달에 채워 연다. 사용자가
// 확인·수정한 뒤 제출하면 기존 텍스트 경로(POST /api/analysis)로 분석이 나간다. s04.md
// US1-3의 "transcript confirm/edit" 단계를 S05a 모달로 구현한 것 — 500자 카운터와 제출
// 로직이 이미 실경로라 중복을 만들지 않는다.
// '텍스트로 입력하기'는 같은 모달을 빈 상태로 연다.
// S05a TextInputSheet 과 같은 상한이다(AnalysisModals.tsx).
const MAX_INPUT_LENGTH = 500;

export function HomeAnalysisCard() {
  const [textSheetOpen, setTextSheetOpen] = useState(false);
  const [transcript, setTranscript] = useState<string | null>(null);

  const recorder = useVoiceRecorder({
    onTranscript: (value) => {
      // S02와 같은 상한으로 자른다. 자르지 않으면 모달 카운터가 "540 / 500"이 되고 제출 시
      // 백엔드가 400을 던져 "연결이 불안정해요"로 끝난다 — 길이 문제라는 걸 알 수 없다.
      // 60초 발화는 500자에 충분히 근접한다.
      setTranscript(value.slice(0, MAX_INPUT_LENGTH));
      setTextSheetOpen(true);
    },
  });

  const closeSheet = () => {
    setTextSheetOpen(false);
    setTranscript(null);
  };

  return (
    <section className="mic-card" aria-label="새 분석 시작">
      <h1 className="mic-card-title">못한 말이 있었나요?</h1>

      <VoiceInput
        recorder={recorder}
        variant="hero"
        onUseText={() => setTextSheetOpen(true)}
      />

      <p className="mic-divider">또는</p>
      <button
        className="text-input-button"
        type="button"
        onClick={() => setTextSheetOpen(true)}
      >
        ✏️ 텍스트로 입력하기
      </button>

      <TextInputSheet
        open={textSheetOpen}
        initialText={transcript ?? undefined}
        onClose={closeSheet}
      />
    </section>
  );
}

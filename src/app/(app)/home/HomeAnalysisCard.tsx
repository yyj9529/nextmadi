"use client";

import { useState } from "react";

import {
  AnalysisLoadingModal,
  TextInputSheet,
} from "@/components/app/AnalysisModals";
import { MicIcon } from "@/components/app/icons";

// S04 새 분석 시작 카드. 마이크는 실제로는 녹음 → POST /transcriptions →
// 전사 확정 → POST /analysis 흐름(스펙 s04/s02). 목 패스에서는 탭 시
// 곧바로 S06 분석 모달을 띄우고 mock-analysis 결과로 라우팅한다.
// '텍스트로 입력하기'는 S05a 모달(POST /analysis 경로)을 연다.
export function HomeAnalysisCard() {
  const [textSheetOpen, setTextSheetOpen] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);

  return (
    <section className="mic-card" aria-label="새 분석 시작">
      <h1 className="mic-card-title">못한 말이 있었나요?</h1>
      <p className="mic-card-sub">탭하고 한국어로 말해보세요</p>
      <div>
        <button
          className="mic-button"
          type="button"
          aria-label="음성으로 말하기"
          onClick={() => setAnalyzing(true)}
        >
          <MicIcon />
        </button>
      </div>
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
        onClose={() => setTextSheetOpen(false)}
      />
      <AnalysisLoadingModal
        open={analyzing}
        onCancel={() => setAnalyzing(false)}
      />
    </section>
  );
}

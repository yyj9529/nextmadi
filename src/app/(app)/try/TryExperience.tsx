"use client";

import { useState } from "react";
import Link from "next/link";

import { AnalysisLoadingModal } from "@/components/app/AnalysisModals";
import { BackIcon, MicIcon } from "@/components/app/icons";

// S02 첫 체험 (비로그인).
// 분석 요청: POST /analysis { input_text } → 201 → /save/result/{id} (S07).
// 음성 입력: POST /transcriptions (STT) → 텍스트 확정 후 동일 경로.
// 목 패스: STT는 1초 뒤 고정 전사를 입력 필드에 채우는 것으로 시뮬레이션.

const MAX_INPUT_LENGTH = 500;
const MOCK_TRANSCRIPT =
  "마트에서 줄 새치기한 사람한테 한마디 하고 싶었는데 영어가 안 떠올랐어요...";

type TryExperienceProps = {
  initialText: string;
};

export function TryExperience({ initialText }: TryExperienceProps) {
  const [text, setText] = useState(initialText);
  const [transcribing, setTranscribing] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);

  const counterClass =
    text.length >= MAX_INPUT_LENGTH
      ? " is-full"
      : text.length >= 450
        ? " is-warning"
        : "";

  const handleMicTap = () => {
    if (transcribing) {
      return;
    }
    setTranscribing(true);
    window.setTimeout(() => {
      setText(MOCK_TRANSCRIPT.slice(0, MAX_INPUT_LENGTH));
      setTranscribing(false);
    }, 1000);
  };

  return (
    <div className="app-screen try-screen">
      <header className="app-topbar result-topbar">
        <Link className="back-link" href="/" aria-label="뒤로 가기">
          <BackIcon />
        </Link>
        <h1 className="result-title">체험하기</h1>
      </header>

      <main className="try-main">
        <section className="try-intro" aria-label="안내">
          <h2 className="try-headline">1분만 투자해보세요</h2>
          <p className="try-sub">회원가입 없이 결과를 볼 수 있어요</p>
        </section>

        <div className="sheet-textarea-wrap">
          <textarea
            className="sheet-textarea try-textarea"
            value={text}
            placeholder="예: 친구한테 서운한 마음을 정중하게 표현하고 싶어요"
            onChange={(event) =>
              setText(event.target.value.slice(0, MAX_INPUT_LENGTH))
            }
          />
          <span className={`char-counter${counterClass}`}>
            {text.length} / {MAX_INPUT_LENGTH}
          </span>
        </div>

        <div className="try-mic-area">
          <button
            className="try-mic-button"
            type="button"
            aria-label="마이크로 말하기"
            onClick={handleMicTap}
          >
            <MicIcon size={26} />
          </button>
          <p className="try-mic-caption">
            {transcribing ? "변환 중..." : "또는 마이크로 말하기"}
          </p>
        </div>

        <button
          className="primary-button try-submit"
          type="button"
          disabled={text.length === 0}
          onClick={() => setAnalyzing(true)}
        >
          분석 요청 →
        </button>
      </main>

      <AnalysisLoadingModal
        open={analyzing}
        onCancel={() => setAnalyzing(false)}
      />
    </div>
  );
}

"use client";

import { checkAnalysisInput, type InputMode, REENTER_MESSAGE } from "@/lib/analysis/input-policy";

export function AnalysisInputGuidance({ text, mode, onMode, disabled = false }: {
  text: string; mode?: InputMode; onMode: (mode: InputMode) => void; disabled?: boolean;
}) {
  const check = checkAnalysisInput(text);
  if (text.trim() && check === "invalid") return <p role="alert">{REENTER_MESSAGE}</p>;
  const choosing = check === "choose_word_intent";
  return (
    <section className="analysis-input-guidance" aria-label="입력 목적">
      {choosing ? <p>이 단어로 무엇을 하고 싶으세요?</p> : null}
      <div className="analysis-intent-options">
        <button type="button" className="secondary-button" aria-pressed={mode === "word"}
          disabled={disabled} onClick={() => onMode("word")}>단어가 궁금해요</button>
        <button type="button" className="secondary-button" aria-pressed={mode === "expressions"}
          disabled={disabled} onClick={() => onMode("expressions")}>할 말을 만들고 싶어요</button>
      </div>
      {choosing && mode === "expressions" ? <p role="status">어떤 말을 하고 싶은지 위 입력창에 적어주세요.</p> : null}
      {mode === "word" ? <p>영어 단어와 뜻을 보여드려요.</p> : null}
    </section>
  );
}

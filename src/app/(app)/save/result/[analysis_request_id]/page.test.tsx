import { describe, expect, mock, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import type { AnalysisResult } from "@/lib/analysis/get-analysis";

let result: AnalysisResult;
mock.module("@/auth", () => ({ auth: async () => ({ user: { id: "owner" } }) }));
mock.module("next/headers", () => ({ cookies: async () => ({ get: () => undefined }) }));
mock.module("@/lib/analysis/get-analysis", () => ({
  getAnalysis: async () => result,
  AnalysisNotFoundError: class extends Error {},
}));
mock.module("@/components/app/PlayButton", () => ({ PlayButton: () => <button>TTS</button> }));
mock.module("@/components/app/AnalysisFollowUp", () => ({ AnalysisFollowUp: () => <button>입력 보충하기</button> }));
mock.module("./ResultActions", () => ({ ResultActions: () => <button>저장하고 연습</button> }));
const { default: Page } = await import("./page");

const base: AnalysisResult = { id: "analysis", input_text: "집주인", created_at: "2026-09-19", variants: [] };
async function render(value: AnalysisResult) {
  result = value;
  return renderToStaticMarkup(await Page({ params: Promise.resolve({ analysis_request_id: "analysis" }) }));
}
const variants = [1, 2, 3].map((order) => ({
  id: String(order), variant_order: order, english_text: `Expression ${order}`,
  tone_label: null, ipa: null, korean_pronunciation: null,
  pronunciation_tip: null, cultural_tip: null, tts_audio_url: null,
}));

describe("result branches (rendering only; no network or audio)", () => {
  test("clarification has a follow-up but no cards, save, practice or TTS", async () => {
    const html = await render({ ...base, result_type: "needs_context", question: "어떤 말인가요?" });
    expect(html).toContain("어떤 말인가요?");
    expect(html).toContain("입력 보충하기");
    for (const text of ["추천 표현", "저장하고 연습", "TTS"]) expect(html).not.toContain(text);
  });
  test("word lookup is distinct from recommendation cards", async () => {
    const html = await render({ ...base, result_type: "word", word: { english: "landlord", meaning_ko: "집주인" } });
    expect(html).toContain("landlord");
    expect(html).toContain("단어 뜻");
    expect(html).not.toContain("추천 표현");
    expect(html).not.toContain("저장하고 연습");
  });
  test("assessment precedes all three expressions and actions", async () => {
    const html = await render({ ...base, variants, result_type: "expressions", assessment: {
      verdict: "appropriate", summary: "적절해요", reason: "거절 의도가 분명해요",
    } });
    expect(html.indexOf("거절 의도가 분명해요")).toBeLessThan(html.indexOf("Expression 1"));
    expect(html.match(/class="expression-card"/g)?.length).toBe(3);
    expect(html).toContain("저장하고 연습");
    expect(html.match(/>TTS</g)?.length).toBe(3);
  });
  test("legacy result without result_type still renders cards and actions", async () => {
    const html = await render({ ...base, variants });
    expect(html).toContain("Expression 3");
    expect(html).toContain("저장하고 연습");
  });
});

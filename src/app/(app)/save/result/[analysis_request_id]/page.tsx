import type { Metadata } from "next";
import Link from "next/link";

import { BackIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import { mockVariants } from "@/lib/mock-api";
import { ResultActions } from "./ResultActions";

export const metadata: Metadata = {
  title: "분석 결과",
};

type AnalysisResultPageProps = {
  params: Promise<{
    analysis_request_id: string;
  }>;
};

// PPT 충실도 패스: s07_result.PNG 목업과 동일한 정적 목 데이터.
// API 연동(GET /analysis/{id}, POST /tts/playback, POST /expressions)은
// 후속 패스에서 교체. 표현 3종은 공용 목(mockVariants)을 사용한다.
const mockAnalysis = {
  koreanInput: "마트에서 줄 새치기한 사람한테 한마디 하고 싶었어요…",
  coachName: "Mia",
  coachMessage: "이런 상황에서 쓸 수 있는 표현 3가지를 찾아봤어요",
  cultureTip: "💡 미국에서는 직접 지적하는 것이 한국보다 자연스러워요",
};

const toneClassByOrder: Record<number, string> = {
  1: "tone-polite",
  2: "tone-direct",
  3: "tone-firm",
};

export default async function AnalysisResultPage({
  params,
}: AnalysisResultPageProps) {
  // 정적 패스: id는 라우트 마운트 확인용으로만 받고 목 데이터를 렌더링한다.
  await params;

  return (
    <div className="app-screen result-screen">
      <div className="result-desktop-grid">
        <main className="result-main-column">
          <header className="app-topbar result-topbar">
            <Link className="back-link" href="/home" aria-label="뒤로 가기">
              <BackIcon />
            </Link>
            <h1 className="result-title">분석 결과</h1>
          </header>

          <p className="input-summary">&ldquo;{mockAnalysis.koreanInput}&rdquo;</p>

          <section className="coach-bubble-row" aria-label="코치 안내">
            <span className="coach-avatar">{mockAnalysis.coachName}</span>
            <p className="coach-bubble">{mockAnalysis.coachMessage}</p>
          </section>
        </main>

        <aside className="result-side-column">
          <ul className="expression-list" aria-label="추천 표현">
            {mockVariants.map((variant) => (
              <li className="expression-card" key={variant.id}>
                <div className="expression-main">
                  <span
                    className={`tone-badge ${toneClassByOrder[variant.variant_order]}`}
                  >
                    {variant.tone_label}
                  </span>
                  <p className="expression-english">{variant.english_text}</p>
                  <p className="expression-pron">
                    {variant.ipa} · {variant.korean_pronunciation}
                  </p>
                </div>
                <PlayButton label={variant.english_text} />
              </li>
            ))}
          </ul>

          <aside className="culture-tip">{mockAnalysis.cultureTip}</aside>
        </aside>

        <ResultActions />
      </div>
    </div>
  );
}

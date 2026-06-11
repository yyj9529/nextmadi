import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "분석 결과",
};

type AnalysisResultPageProps = {
  params: Promise<{
    analysis_request_id: string;
  }>;
};

// PPT 충실도 패스: s07_result.PNG 목업과 동일한 정적 목 데이터.
// API 연동(GET /analysis/{id}, POST /tts/playback, POST /expressions)은 후속 패스에서 교체.
const mockAnalysis = {
  koreanInput: "마트에서 줄 새치기한 사람한테 한마디 하고 싶었어요…",
  coachName: "Mia",
  coachMessage: "이런 상황에서 쓸 수 있는 표현 3가지를 찾아봤어요",
  variants: [
    {
      order: 1,
      tone: "정중한",
      toneClass: "tone-polite",
      english: "Excuse me, I think there's a line.",
      ipa: "/ɪkˈskjuːz miː, aɪ θɪŋk ðɛrz ə laɪn/",
      koreanPhonetic: "익스큐즈 미, 아이 띵크 데어즈 어 라인",
    },
    {
      order: 2,
      tone: "직설적",
      toneClass: "tone-direct",
      english: "Hey, the line starts back there.",
      ipa: "/heɪ, ðə laɪn stɑːrts bæk ðɛr/",
      koreanPhonetic: "헤이, 더 라인 스타츠 백 데어",
    },
    {
      order: 3,
      tone: "단호한",
      toneClass: "tone-firm",
      english: "Please go to the back of the line.",
      ipa: "/pliːz ɡoʊ tə ðə bæk əv ðə laɪn/",
      koreanPhonetic: "플리즈 고 투 더 백 오브 더 라인",
    },
  ],
  cultureTip: "💡 미국에서는 직접 지적하는 것이 한국보다 자연스러워요",
};

function BackIcon() {
  return (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M19 12H5" />
      <path d="M12 19l-7-7 7-7" />
    </svg>
  );
}

function PlayIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="M8 5.14v13.72c0 .92 1.02 1.48 1.8.98l10.18-6.86c.7-.47.7-1.5 0-1.96L9.8 4.16c-.78-.5-1.8.06-1.8.98z" />
    </svg>
  );
}

function RetryIcon() {
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M3 12a9 9 0 1 0 2.64-6.36L3 8" />
      <path d="M3 3v5h5" />
    </svg>
  );
}

export default async function AnalysisResultPage({
  params,
}: AnalysisResultPageProps) {
  // 정적 패스: id는 라우트 마운트 확인용으로만 받고 목 데이터를 렌더링한다.
  await params;

  return (
    <div className="app-screen">
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

      <ul className="expression-list" aria-label="추천 표현">
        {mockAnalysis.variants.map((variant) => (
          <li className="expression-card" key={variant.order}>
            <div className="expression-main">
              <span className={`tone-badge ${variant.toneClass}`}>
                {variant.tone}
              </span>
              <p className="expression-english">{variant.english}</p>
              <p className="expression-pron">
                {variant.ipa} · {variant.koreanPhonetic}
              </p>
            </div>
            <button
              className="play-button"
              type="button"
              aria-label={`${variant.english} 재생`}
            >
              <PlayIcon />
            </button>
          </li>
        ))}
      </ul>

      <aside className="culture-tip">{mockAnalysis.cultureTip}</aside>

      <div className="result-actions">
        <button className="save-button" type="button">
          저장하기
        </button>
        <button className="retry-button" type="button">
          <RetryIcon /> 다시
        </button>
      </div>
    </div>
  );
}

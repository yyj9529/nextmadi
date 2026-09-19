import type { Metadata } from "next";
import Link from "next/link";
import { cookies } from "next/headers";

import { auth } from "@/auth";
import { BackIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import {
  type AnalysisResult,
  type AnalysisVariant,
  AnalysisNotFoundError,
  getAnalysis,
} from "@/lib/analysis/get-analysis";
import { ResultActions } from "./ResultActions";
import { AnalysisFollowUp } from "@/components/app/AnalysisFollowUp";

export const metadata: Metadata = {
  title: "분석 결과",
};

type AnalysisResultPageProps = {
  params: Promise<{
    analysis_request_id: string;
  }>;
};

// variant_order(1=정중 / 2=직설 / 3=단호 경향)에 따른 톤 배지 색. tone_label 자체는
// AI가 만든 동적 한국어이므로 enum이 아니다(openapi). 색은 순서로만 고정한다.
const toneClassByOrder: Record<number, string> = {
  1: "tone-polite",
  2: "tone-direct",
  3: "tone-firm",
};

export default async function AnalysisResultPage({
  params,
}: AnalysisResultPageProps) {
  const { analysis_request_id: analysisRequestId } = await params;
  const session = await auth();
  const userId = session?.user?.id;
  const isAuthenticated = Boolean(userId);

  // 가입 전(S02) 익명 접근은 httpOnly 쿠키의 session_token으로 소유권을 증명한다.
  const cookieStore = await cookies();
  const sessionToken = cookieStore.get(ANON_SESSION_COOKIE)?.value;

  // 인증도 익명 세션도 없으면 이 분석을 소유할 수 없다 → not-found로 처리한다.
  if (!userId && !sessionToken) {
    return <NotFoundResult isAuthenticated={isAuthenticated} />;
  }

  let analysis: AnalysisResult;
  try {
    analysis = await getAnalysis({
      analysisRequestId,
      ...(userId ? { userId } : { sessionToken }),
    });
  } catch (error) {
    if (error instanceof AnalysisNotFoundError) {
      return <NotFoundResult isAuthenticated={isAuthenticated} />;
    }
    // 백엔드 오류/네트워크: 사용자는 소유 여부를 구분할 수 없으므로 동일한 안내를 보여준다.
    // 원문/토큰은 로깅하지 않는다(SECURITY.md).
    return <NotFoundResult isAuthenticated={isAuthenticated} />;
  }

  const variants = [...analysis.variants].sort(
    (a, b) => a.variant_order - b.variant_order,
  );

  return (
    <div className="app-screen result-screen">
      <div className="result-desktop-grid">
        <main className="result-main-column">
          <header className="app-topbar result-topbar">
            <Link
              className="back-link"
              href={isAuthenticated ? "/home" : "/try"}
              aria-label="뒤로 가기"
            >
              <BackIcon />
            </Link>
            <h1 className="result-title">분석 결과</h1>
          </header>

          <p className="input-summary">&ldquo;{analysis.input_text}&rdquo;</p>
          {analysis.result_type === "needs_context" ? (
            <section aria-label="입력 보충">
              <h2>조금만 더 알려주세요</h2>
              <p>{analysis.question}</p>
              <AnalysisFollowUp inputText={analysis.input_text} />
            </section>
          ) : analysis.result_type === "word" ? (
            <section aria-label="단어 뜻">
              <h2>{analysis.word?.english}</h2>
              <p>{analysis.word?.meaning_ko}</p>
              <AnalysisFollowUp inputText={analysis.input_text} />
            </section>
          ) : analysis.assessment ? (
            <section className="analysis-assessment" aria-label="분석과 이유">
              <h2>{analysis.assessment.summary}</h2>
              <p>{analysis.assessment.reason}</p>
              {analysis.assessment.verdict === "appropriate" ? <p>원문도 적절해요. 아래는 다른 말투의 선택지예요.</p> : null}
            </section>
          ) : null}
        </main>

        {(!analysis.result_type || analysis.result_type === "expressions") && variants.length === 3 ? <>
        <aside className="result-side-column">
          <ul className="expression-list" aria-label="추천 표현">
            {variants.map((variant) => (
              <ExpressionCard key={variant.id} variant={variant} />
            ))}
          </ul>
        </aside>

        <ResultActions
          analysisRequestId={analysisRequestId}
          isAuthenticated={isAuthenticated}
        />
        </> : null}
      </div>
    </div>
  );
}

function ExpressionCard({ variant }: { variant: AnalysisVariant }) {
  return (
    <li className="expression-card">
      <div className="expression-main">
        {variant.tone_label ? (
          <span
            className={`tone-badge ${toneClassByOrder[variant.variant_order] ?? ""}`}
          >
            {variant.tone_label}
          </span>
        ) : null}
        <p className="expression-english">{variant.english_text}</p>
        {variant.ipa || variant.korean_pronunciation ? (
          <p className="expression-pron">
            {[variant.ipa, variant.korean_pronunciation]
              .filter(Boolean)
              .join(" · ")}
          </p>
        ) : null}
        {variant.pronunciation_tip ? (
          <p className="expression-tip">{variant.pronunciation_tip}</p>
        ) : null}
        {variant.cultural_tip ? (
          <p className="expression-tip culture">{variant.cultural_tip}</p>
        ) : null}
      </div>
      <PlayButton label={variant.english_text} />
    </li>
  );
}

function NotFoundResult({ isAuthenticated }: { isAuthenticated: boolean }) {
  // S07 US1-AC3 / edge case: 없거나 소유하지 않은 id. 인증은 /home, 가입 전은 /try로.
  const ctaHref = isAuthenticated ? "/home" : "/try";
  const ctaLabel = isAuthenticated ? "홈으로" : "다시 시도하기";
  return (
    <div className="app-screen result-screen">
      <div className="result-notfound" role="alert">
        <p className="result-notfound-title">찾을 수 없는 결과예요</p>
        <p className="result-notfound-copy">
          시간이 지나 만료되었거나, 잘못된 주소일 수 있어요.
        </p>
        <Link className="primary-button" href={ctaHref}>
          {ctaLabel}
        </Link>
      </div>
    </div>
  );
}

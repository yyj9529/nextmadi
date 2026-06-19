"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { AnalysisLoadingModal } from "@/components/app/AnalysisModals";
import { BackIcon, MicIcon } from "@/components/app/icons";
import { MOCK_ANALYSIS_ID } from "@/lib/mock-api";

// S02 첫 체험 (비로그인).
// 실제 BFF 연동 전까지 기본 제출 함수는 mock-only라 live AI provider를 호출하지 않는다.

const MAX_INPUT_LENGTH = 500;
const MOCK_TRANSCRIPT =
  "마트에서 줄 새치기한 사람한테 한마디 하고 싶었는데 영어가 안 떠올랐어요...";
const RATE_LIMIT_ERROR_CODE = "rate_limit_exceeded";

export type TryAnalysisResult = {
  analysis_request_id: string;
};

export type TryAnalysisSubmitter = (
  inputText: string,
) => Promise<TryAnalysisResult>;

type TryExperienceProps = {
  initialText: string;
  submitAnalysis?: TryAnalysisSubmitter;
};

export function isRateLimitExceededError(error: unknown) {
  if (typeof error !== "object" || error === null) {
    return false;
  }

  return "error_code" in error && error.error_code === RATE_LIMIT_ERROR_CODE;
}

async function mockSubmitTryAnalysis(): Promise<TryAnalysisResult> {
  await new Promise((resolve) => window.setTimeout(resolve, 700));
  return { analysis_request_id: MOCK_ANALYSIS_ID };
}

export function TryExperience({
  initialText,
  submitAnalysis = mockSubmitTryAnalysis,
}: TryExperienceProps) {
  const router = useRouter();
  const [text, setText] = useState(initialText);
  const [transcribing, setTranscribing] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [networkError, setNetworkError] = useState(false);
  const activeSubmitRef = useRef(0);

  const counterClass =
    text.length >= MAX_INPUT_LENGTH
      ? " is-full"
      : text.length >= 450
        ? " is-warning"
        : "";

  const handleMicTap = () => {
    if (transcribing || analyzing || rateLimited) {
      return;
    }

    setTranscribing(true);
    window.setTimeout(() => {
      setText(MOCK_TRANSCRIPT.slice(0, MAX_INPUT_LENGTH));
      setTranscribing(false);
    }, 1000);
  };

  const handleSubmit = async () => {
    if (text.length === 0 || analyzing || rateLimited) {
      return;
    }

    const submitId = activeSubmitRef.current + 1;
    activeSubmitRef.current = submitId;
    setNetworkError(false);
    setAnalyzing(true);

    try {
      const result = await submitAnalysis(text);
      if (activeSubmitRef.current !== submitId) {
        return;
      }

      router.push(`/save/result/${result.analysis_request_id}`);
    } catch (error) {
      if (activeSubmitRef.current !== submitId) {
        return;
      }

      if (isRateLimitExceededError(error)) {
        setRateLimited(true);
      } else {
        setNetworkError(true);
      }
      setAnalyzing(false);
    }
  };

  const handleCancel = () => {
    activeSubmitRef.current += 1;
    setAnalyzing(false);
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

        {rateLimited ? (
          <section className="try-rate-limit-state" aria-live="polite">
            <h2 className="try-rate-limit-title">
              오늘 무료 분석을 모두 썼어요
            </h2>
            <p className="try-rate-limit-copy">
              가입하면 표현을 계속 저장하고 연습할 수 있어요.
            </p>
            <Link className="primary-button try-rate-limit-cta" href="/login">
              회원가입하기
            </Link>
            <p className="try-rate-limit-reset">내일 다시 시도</p>
          </section>
        ) : (
          <>
            <div className="sheet-textarea-wrap">
              <textarea
                className="sheet-textarea try-textarea"
                value={text}
                placeholder="예: 친구한테 서운한 마음을 정중하게 표현하고 싶어요"
                readOnly={analyzing}
                onChange={(event) =>
                  setText(event.target.value.slice(0, MAX_INPUT_LENGTH))
                }
              />
              <span className={`char-counter${counterClass}`}>
                {text.length} / {MAX_INPUT_LENGTH}
              </span>
            </div>

            {networkError ? (
              <p className="try-error-toast" role="alert">
                연결이 불안정해요. 다시 시도해주세요.
              </p>
            ) : null}

            <div className="try-mic-area">
              <button
                className="try-mic-button"
                type="button"
                aria-label="마이크로 말하기"
                onClick={handleMicTap}
                disabled={analyzing}
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
              disabled={text.length === 0 || analyzing}
              onClick={handleSubmit}
            >
              분석 요청
            </button>
          </>
        )}
      </main>

      <AnalysisLoadingModal
        open={analyzing}
        autoRoute={false}
        onCancel={handleCancel}
      />
    </div>
  );
}

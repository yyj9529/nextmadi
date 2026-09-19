"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { AnalysisLoadingModal } from "@/components/app/AnalysisModals";
import { AnalysisInputGuidance } from "@/components/app/AnalysisInputGuidance";
import { checkAnalysisInput, type InputMode } from "@/lib/analysis/input-policy";
import { AnalysisOperation, postAnalysisRequest, type AnalysisClientOptions } from "@/lib/analysis/client-request";
import { BackIcon } from "@/components/app/icons";
import { VoiceInput } from "@/components/app/VoiceInput";
import { useVoiceRecorder } from "@/lib/voice/use-voice-recorder";

// S02 첫 체험 (비로그인).
// 기본 제출 함수는 BFF 라우트 POST /api/analysis를 호출한다. 익명 session_token은 라우트가
// httpOnly 쿠키로 관리하므로 클라이언트는 input_text만 보낸다(ADR-010). 테스트에서는
// submitAnalysis prop으로 mock을 주입한다.
//
// 음성 입력(#36)은 2-스텝이다: 녹음 → POST /api/transcriptions → 전사문을 아래 입력창에
// 채워 사용자가 확인·편집 → 기존 텍스트 제출 경로로 분석. 오전사가 분석 호출과 익명 2회
// 한도를 태우지 않게 하려는 것이다(s02.md US1-4).

const MAX_INPUT_LENGTH = 500;
const RATE_LIMIT_ERROR_CODE = "rate_limit_exceeded";

export type TryAnalysisResult = {
  analysis_request_id: string;
};

export type TryAnalysisSubmitter = (
  inputText: string,
  options?: AnalysisClientOptions,
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

// 브라우저 요청 타임아웃(ms). 이 값이 지나면 fetch를 우리가 취소한다. 타임아웃 시 AbortSignal이
// fetch를 reject하면 handleSubmit의 catch가 잡아 기존 networkError 토스트("연결이 불안정해요")를
// 재사용한다.
//
// 기준은 s07_analysis의 서버 예산 60초(AI_PIPELINE.md, docs/screens/s06.md). 거기에 응답이
// 돌아오는 시간만큼 여유를 더한다 — 정확히 동률로 두면 클라이언트가 먼저 끊어, 백엔드는 계속
// 일하고 과금하는데 사용자만 에러를 보는 경합이 생긴다. 여유를 두면 백엔드 자신의 타임아웃
// 에러가 이기므로 사용자는 파이프라인이 분류한 에러를 본다.
const ANALYSIS_SERVER_BUDGET_MS = 60_000;
const TRANSPORT_MARGIN_MS = 5_000;
const REQUEST_TIMEOUT_MS = ANALYSIS_SERVER_BUDGET_MS + TRANSPORT_MARGIN_MS;

// 실제 제출: BFF 라우트로 input_text를 보낸다. 비-ok 응답은 본문 JSON(있으면 error_code 포함)을
// throw해 handleSubmit의 rate-limit / 네트워크 에러 분기가 그대로 동작하게 한다.
async function postTryAnalysis(inputText: string, options?: AnalysisClientOptions): Promise<TryAnalysisResult> {
  return postAnalysisRequest(inputText, AbortSignal.timeout(REQUEST_TIMEOUT_MS), options);
}

export function TryExperience({
  initialText,
  submitAnalysis = postTryAnalysis,
}: TryExperienceProps) {
  const router = useRouter();
  const [text, setText] = useState(initialText);
  const [analyzing, setAnalyzing] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [networkError, setNetworkError] = useState(false);
  const activeSubmitRef = useRef(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [inputMode, setInputMode] = useState<InputMode>();
  const operation = useRef(new AnalysisOperation());
  const submitLock = useRef(false);
  const inputCheck = checkAnalysisInput(text);
  const canSubmit = inputCheck !== "invalid" && (inputCheck !== "choose_word_intent" || inputMode === "word");
  const changeText = (next: string) => {
    operation.current.reset(); setInputMode(undefined); setNetworkError(false); setText(next);
  };

  const counterClass =
    text.length >= MAX_INPUT_LENGTH
      ? " is-full"
      : text.length >= 450
        ? " is-warning"
        : "";

  // 전사문은 제출하지 않고 입력창에 채우기만 한다 — 사용자가 확인·수정한 뒤 직접 제출한다.
  const recorder = useVoiceRecorder({
    onTranscript: (transcript) => changeText(transcript.slice(0, MAX_INPUT_LENGTH)),
  });

  const handleSubmit = async () => {
    if (!canSubmit || analyzing || rateLimited || submitLock.current) {
      return;
    }
    submitLock.current = true;

    const submitId = activeSubmitRef.current + 1;
    activeSubmitRef.current = submitId;
    setNetworkError(false);
    setAnalyzing(true);

    try {
      const result = await submitAnalysis(text, { inputMode, idempotencyKey: operation.current.current() });
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
    } finally {
      submitLock.current = false;
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
                ref={textareaRef}
                className="sheet-textarea try-textarea"
                value={text}
                placeholder="예: 친구한테 서운한 마음을 정중하게 표현하고 싶어요"
                readOnly={analyzing}
                onChange={(event) =>
                  changeText(event.target.value.slice(0, MAX_INPUT_LENGTH))
                }
              />
              <span className={`char-counter${counterClass}`}>
                {text.length} / {MAX_INPUT_LENGTH}
              </span>
            </div>

            <AnalysisInputGuidance text={text} mode={inputMode} disabled={analyzing} onMode={(mode) => {
              operation.current.reset(); setInputMode(mode); textareaRef.current?.focus();
            }} />

            {networkError ? (
              <p className="try-error-toast" role="alert">
                연결이 불안정해요. 다시 시도해주세요.
              </p>
            ) : null}

            <div className="try-mic-area">
              <VoiceInput
                recorder={recorder}
                disabled={analyzing}
                // 마이크를 쓸 수 없는 상태(권한 거부·미지원·한도 초과)에서 빠져나갈 길.
                // 입력창은 이미 화면에 있으므로 새로 열지 않고 포커스만 옮긴다.
                onUseText={() => textareaRef.current?.focus()}
              />
            </div>

            <button
              className="primary-button try-submit"
              type="button"
              disabled={!canSubmit || analyzing}
              onClick={handleSubmit}
            >
              분석 요청
            </button>
          </>
        )}
      </main>

      <AnalysisLoadingModal open={analyzing} onCancel={handleCancel} />
    </div>
  );
}

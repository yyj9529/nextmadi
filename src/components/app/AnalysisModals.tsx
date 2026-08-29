"use client";

import { type ClipboardEvent, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { CloseIcon } from "@/components/app/icons";
import {
  clearDraftInput,
  readDraftInput,
  writeDraftInput,
} from "@/lib/analysis/draft-input";

// S06 분석 진행 모달 + S05a 텍스트 입력 모달.
// POST /analysis { input_text } -> 201 -> /save/result/{id}. 라우팅은 제출한 화면이 응답을
// 받고 직접 한다 — 모달은 진행 표시와 취소만 맡는다(#36에서 목 자동 라우팅 제거).

const LOADING_STEPS = [
  "상황을 분석하고 있어요...",
  "적합한 표현을 찾고 있어요...",
  "발음과 문화 정보 정리 중...",
];

const STEP_MS = 800;
const CANCEL_AFTER_MS = 10000; // 스펙: 10초 경과 시 취소 버튼 노출

type AnalysisLoadingModalProps = {
  open: boolean;
  onCancel: () => void;
};

export function AnalysisLoadingModal({
  open,
  onCancel,
}: AnalysisLoadingModalProps) {
  if (!open) {
    return null;
  }

  return <AnalysisLoadingModalBody onCancel={onCancel} />;
}

// open 동안에만 마운트되어 상태가 자연스럽게 초기화된다.
function AnalysisLoadingModalBody({ onCancel }: { onCancel: () => void }) {
  const [stepIndex, setStepIndex] = useState(0);
  const [showCancel, setShowCancel] = useState(false);

  useEffect(() => {
    const stepTimers = LOADING_STEPS.map((_, index) =>
      window.setTimeout(() => setStepIndex(index), index * STEP_MS),
    );
    const cancelTimer = window.setTimeout(
      () => setShowCancel(true),
      CANCEL_AFTER_MS,
    );

    return () => {
      stepTimers.forEach((timer) => window.clearTimeout(timer));
      window.clearTimeout(cancelTimer);
    };
  }, []);

  return (
    <div className="modal-backdrop" role="presentation">
      <div
        className="loading-modal"
        role="dialog"
        aria-modal="true"
        aria-label="분석 진행 중"
      >
        <span className="loading-spinner" aria-hidden="true" />
        <p className="loading-headline">분석 중...</p>
        <p className="loading-step" aria-live="polite">
          {LOADING_STEPS[stepIndex]}
        </p>
        {showCancel ? (
          <button className="loading-cancel" type="button" onClick={onCancel}>
            취소
          </button>
        ) : null}
      </div>
    </div>
  );
}

const MAX_INPUT_LENGTH = 500;
const RATE_LIMIT_ERROR_CODE = "rate_limit_exceeded";
const PASTE_LIMIT_TOAST = "최대 500자까지 가능해요";
const PASTE_TOAST_MS = 2500;

export type AnalysisSubmitResult = {
  analysis_request_id: string;
};

// AbortSignal을 받아 진행 중 취소(모달 닫기)를 지원한다(스펙 s05a US1-AC5, edge: in-flight 취소).
export type AnalysisSubmitter = (
  inputText: string,
  signal: AbortSignal,
) => Promise<AnalysisSubmitResult>;

// S02 TryExperience와 동일한 정규화: BFF가 429를 rate_limit_exceeded로 내려준다(ADR-010).
function isRateLimitExceededError(error: unknown): boolean {
  if (typeof error !== "object" || error === null) {
    return false;
  }
  return "error_code" in error && error.error_code === RATE_LIMIT_ERROR_CODE;
}

// 실제 제출: BFF 라우트 POST /api/analysis. 익명 session_token은 라우트가 httpOnly 쿠키로
// 관리하므로 클라이언트는 input_text만 보낸다(ADR-010). 비-ok 응답은 본문 JSON(있으면
// error_code 포함)을 throw해 rate-limit / 네트워크 에러 분기가 동작하게 한다.
async function postAnalysis(
  inputText: string,
  signal: AbortSignal,
): Promise<AnalysisSubmitResult> {
  const response = await fetch("/api/analysis", {
    method: "POST",
    headers: { "content-type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ input_text: inputText }),
    signal,
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      // 본문 파싱 실패: 네트워크 에러로 취급(아래 throw).
    }
    throw errorBody ?? new Error(`analysis failed: ${response.status}`);
  }

  return (await response.json()) as AnalysisSubmitResult;
}

type TextInputSheetProps = {
  open: boolean;
  onClose: () => void;
  /**
   * 음성 전사문(#36). 주어지면 초안 복원 대신 이 값으로 연다 — 방금 말한 내용이
   * 예전 초안보다 항상 우선이다. 사용자는 여기서 확인·수정한 뒤 제출한다.
   */
  initialText?: string;
  /** 테스트에서 실제 fetch 대신 주입. 기본은 POST /api/analysis. */
  submitAnalysis?: AnalysisSubmitter;
};

// open일 때만 Body를 마운트해 매 열기마다 상태를 초기화한다 — 초안 복원/제출/취소 로직이
// 깨끗한 생명주기 위에서 동작한다(AnalysisLoadingModal과 같은 패턴).
export function TextInputSheet({
  open,
  onClose,
  initialText,
  submitAnalysis = postAnalysis,
}: TextInputSheetProps) {
  if (!open) {
    return null;
  }
  return (
    <TextInputSheetBody
      key={initialText ?? ""}
      onClose={onClose}
      initialText={initialText}
      submitAnalysis={submitAnalysis}
    />
  );
}

function TextInputSheetBody({
  onClose,
  initialText,
  submitAnalysis,
}: {
  onClose: () => void;
  initialText?: string;
  submitAnalysis: AnalysisSubmitter;
}) {
  const router = useRouter();
  // 전사문이 있으면 그것으로, 없으면 5분 내 초안 복원(readDraftInput이 stale을 정리).
  const [text, setText] = useState<string>(
    () => initialText ?? readDraftInput() ?? "",
  );
  const [submitting, setSubmitting] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [networkError, setNetworkError] = useState(false);
  const [pasteToast, setPasteToast] = useState(false);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const pasteToastTimer = useRef<number | null>(null);
  // 언마운트(닫기) 시점의 최신 텍스트/성공 여부를 클로저 없이 읽기 위한 ref.
  const textRef = useRef(text);
  const submittedRef = useRef(false);

  // 매 커밋 후 최신 텍스트를 ref에 반영해, 언마운트 cleanup이 최신 값을 초안으로 저장하게 한다.
  useEffect(() => {
    textRef.current = text;
  });

  useEffect(() => {
    textareaRef.current?.focus();
    return () => {
      // 닫힘: 진행 중 요청 취소. 성공 제출이면 초안 삭제, 아니면(취소 포함) 초안 보존.
      abortRef.current?.abort();
      if (submittedRef.current) {
        clearDraftInput();
      } else {
        writeDraftInput(textRef.current);
      }
      if (pasteToastTimer.current !== null) {
        window.clearTimeout(pasteToastTimer.current);
      }
    };
  }, []);

  const showPasteToast = () => {
    setPasteToast(true);
    if (pasteToastTimer.current !== null) {
      window.clearTimeout(pasteToastTimer.current);
    }
    pasteToastTimer.current = window.setTimeout(
      () => setPasteToast(false),
      PASTE_TOAST_MS,
    );
  };

  // 붙여넣기가 500자를 넘기면 기본 동작을 막고 남은 용량까지 잘라 넣은 뒤 1회성 토스트를 띄운다.
  const handlePaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const el = event.currentTarget;
    const start = el.selectionStart ?? text.length;
    const end = el.selectionEnd ?? text.length;
    const pasted = event.clipboardData.getData("text");
    const next = text.slice(0, start) + pasted + text.slice(end);
    if (next.length > MAX_INPUT_LENGTH) {
      event.preventDefault();
      setText(next.slice(0, MAX_INPUT_LENGTH));
      showPasteToast();
    }
  };

  const handleSubmit = async () => {
    if (text.length === 0 || submitting || rateLimited) {
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;
    setNetworkError(false);
    setSubmitting(true);

    try {
      const result = await submitAnalysis(text, controller.signal);
      if (controller.signal.aborted) {
        return;
      }
      // 성공: 초안 삭제 후 모달을 닫고 S07로 이동한다.
      submittedRef.current = true;
      clearDraftInput();
      onClose();
      router.push(`/save/result/${result.analysis_request_id}`);
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      if (isRateLimitExceededError(error)) {
        setRateLimited(true);
      } else {
        setNetworkError(true);
      }
      setSubmitting(false);
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
    }
  };

  const counterClass =
    text.length >= MAX_INPUT_LENGTH
      ? " is-full"
      : text.length >= 450
        ? " is-warning"
        : "";

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="text-input-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="텍스트로 입력"
        onClick={(event) => event.stopPropagation()}
      >
        <span className="sheet-handle" aria-hidden="true" />
        <div className="sheet-header">
          <h2 className="sheet-title">텍스트로 입력</h2>
          <button
            className="icon-button"
            type="button"
            aria-label="닫기"
            onClick={onClose}
          >
            <CloseIcon />
          </button>
        </div>

        {rateLimited ? (
          // 429: 입력 영역을 S02와 같은 rate-limit 문구 + 가입 CTA로 교체한다(스펙 edge case).
          <section className="sheet-rate-limit-state" aria-live="polite">
            <h3 className="sheet-rate-limit-title">
              오늘 무료 분석을 모두 썼어요
            </h3>
            <p className="sheet-rate-limit-copy">
              가입하면 표현을 계속 저장하고 연습할 수 있어요.
            </p>
            <Link
              className="primary-button sheet-rate-limit-cta"
              href="/login"
            >
              회원가입하기
            </Link>
            <p className="sheet-rate-limit-reset">내일 다시 시도</p>
          </section>
        ) : (
          <>
            <p className="sheet-sub">긴 상황은 여기서 자세히 적어주세요</p>
            <div className="sheet-textarea-wrap">
              <textarea
                ref={textareaRef}
                className="sheet-textarea"
                value={text}
                readOnly={submitting}
                placeholder={
                  "마트에서 줄 새치기한 사람한테\n한마디 하고 싶었는데 영어가\n안 떠올랐어요..."
                }
                onChange={(event) =>
                  setText(event.target.value.slice(0, MAX_INPUT_LENGTH))
                }
                onPaste={handlePaste}
              />
              <span className={`char-counter${counterClass}`}>
                {text.length} / {MAX_INPUT_LENGTH}
              </span>
              {pasteToast ? (
                <span className="sheet-paste-toast" role="status">
                  {PASTE_LIMIT_TOAST}
                </span>
              ) : null}
            </div>

            {networkError ? (
              <p className="sheet-error-toast" role="alert">
                연결이 불안정해요. 다시 시도해주세요.
              </p>
            ) : null}

            <button
              className="primary-button"
              type="button"
              disabled={text.length === 0 || submitting}
              onClick={handleSubmit}
            >
              {submitting ? (
                <span className="loading-spinner" aria-hidden="true" />
              ) : (
                "분석 요청"
              )}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

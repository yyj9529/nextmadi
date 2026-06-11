"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { CloseIcon } from "@/components/app/icons";
import { mockAnalysisResultPath } from "@/lib/mock-api";

// S06 분석 진행 모달 + S05a 텍스트 입력 모달.
// 실제 구현: POST /analysis { input_text } → 201 → /save/result/{id}.
// 목 패스: 단계 메시지 3개를 순환한 뒤 mock-analysis 결과로 라우팅한다.

const LOADING_STEPS = [
  "상황을 분석하고 있어요…",
  "적합한 표현을 찾고 있어요…",
  "발음과 문화 정보 정리 중…",
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
  const router = useRouter();
  const [stepIndex, setStepIndex] = useState(0);
  const [showCancel, setShowCancel] = useState(false);

  useEffect(() => {
    const stepTimers = LOADING_STEPS.map((_, index) =>
      window.setTimeout(() => setStepIndex(index), index * STEP_MS),
    );
    const doneTimer = window.setTimeout(() => {
      router.push(mockAnalysisResultPath);
    }, LOADING_STEPS.length * STEP_MS);
    const cancelTimer = window.setTimeout(
      () => setShowCancel(true),
      CANCEL_AFTER_MS,
    );

    return () => {
      stepTimers.forEach((timer) => window.clearTimeout(timer));
      window.clearTimeout(doneTimer);
      window.clearTimeout(cancelTimer);
    };
  }, [router]);

  return (
    <div className="modal-backdrop" role="presentation">
      <div
        className="loading-modal"
        role="dialog"
        aria-modal="true"
        aria-label="분석 진행 중"
      >
        <span className="loading-spinner" aria-hidden="true" />
        <p className="loading-headline">✨ 분석 중...</p>
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

type TextInputSheetProps = {
  open: boolean;
  onClose: () => void;
  onSubmit: (text: string) => void;
};

export function TextInputSheet({
  open,
  onClose,
  onSubmit,
}: TextInputSheetProps) {
  const [text, setText] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (open) {
      textareaRef.current?.focus();
    }
  }, [open]);

  if (!open) {
    return null;
  }

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
          <h2 className="sheet-title">✏️ 텍스트로 입력</h2>
          <button
            className="icon-button"
            type="button"
            aria-label="닫기"
            onClick={onClose}
          >
            <CloseIcon />
          </button>
        </div>
        <p className="sheet-sub">긴 상황은 여기서 자세히 적어주세요</p>
        <div className="sheet-textarea-wrap">
          <textarea
            ref={textareaRef}
            className="sheet-textarea"
            value={text}
            placeholder={
              "“마트에서 줄 새치기한 사람한테\n한마디 하고 싶었는데 영어가\n안 떠올랐어요...”"
            }
            onChange={(event) =>
              setText(event.target.value.slice(0, MAX_INPUT_LENGTH))
            }
          />
          <span className={`char-counter${counterClass}`}>
            {text.length} / {MAX_INPUT_LENGTH}
          </span>
        </div>
        <button
          className="primary-button"
          type="button"
          disabled={text.length === 0}
          onClick={() => onSubmit(text)}
        >
          분석 요청 →
        </button>
      </div>
    </div>
  );
}

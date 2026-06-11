"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { CloseIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import { mockReviewQueue } from "@/lib/mock-api";

// S10 복습 화면.
// 1단계: 한국어 상황만 노출(active recall) → '정답 보기'.
// 2단계: 영어 표현 카드 + 평가 3버튼이 함께 노출.
// 평가 제출: POST /review/{review_card_id}/submit { rating } →
// hard=1일, good=이전×2, easy=이전×3 (스펙 s10). 목 패스: 로컬 계산.

type Rating = "hard" | "good" | "easy";

export function ReviewExperience() {
  const router = useRouter();
  const { cards, total_due } = mockReviewQueue;

  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [doneCount, setDoneCount] = useState(0);
  const [toast, setToast] = useState<string | null>(null);
  const [confirmExit, setConfirmExit] = useState(false);

  const card = index < cards.length ? cards[index] : null;
  const completed = card === null;

  const submitRating = (rating: Rating) => {
    if (!card) {
      return;
    }
    const base = card.current_interval_days;
    const next = rating === "hard" ? 1 : rating === "good" ? base * 2 : base * 3;
    setToast(
      rating === "hard"
        ? "📅 내일 다시 만나요"
        : rating === "good"
          ? `📅 ${next}일 후 다시`
          : `🎉 ${next}일 후 다시!`,
    );
    setDoneCount((count) => count + 1);
    setIndex((current) => current + 1);
    setRevealed(false);
  };

  if (completed) {
    return (
      <div className="app-screen review-screen">
        <main className="review-complete">
          <p className="review-complete-emoji" aria-hidden="true">
            🎉
          </p>
          <h1 className="review-complete-title">
            오늘 {doneCount}개 복습 완료
          </h1>
          <p className="review-complete-sub">
            책장이 더 단단해졌어요. 내일 또 만나요!
          </p>
          <button
            className="primary-button"
            type="button"
            onClick={() => router.push("/home")}
          >
            홈으로
          </button>
        </main>
      </div>
    );
  }

  return (
    <div className="app-screen review-screen">
      <header className="review-topbar">
        <button
          className="icon-button"
          type="button"
          aria-label="복습 종료"
          onClick={() => setConfirmExit(true)}
        >
          <CloseIcon />
        </button>
        <span className="review-progress-label">
          {index + 1} / {total_due}
        </span>
      </header>
      <div
        className="review-progress-track"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={total_due}
        aria-valuenow={index}
      >
        <span
          className="review-progress-fill"
          style={{ width: `${(index / total_due) * 100}%` }}
        />
      </div>

      <main className="review-main">
        {!revealed ? (
          <>
            <p className="review-caption">이 상황을 어떻게 말할까요?</p>
            <div className="review-question-card">
              “{card.original_situation}”
            </div>
            <button
              className="primary-button review-reveal"
              type="button"
              onClick={() => setRevealed(true)}
            >
              정답 보기 →
            </button>
          </>
        ) : (
          <>
            <p className="review-caption review-caption-small">
              “{card.original_situation}”
            </p>
            <div className="review-answer-card">
              <div className="detail-variant-pron-row">
                <p className="review-answer-english">
                  {card.variant.english_text}
                </p>
                <PlayButton label={card.variant.english_text} />
              </div>
              <p className="expression-pron">
                {card.variant.ipa} · {card.variant.korean_pronunciation}
              </p>
            </div>

            <p className="review-rate-caption">기억하셨나요?</p>
            <div className="review-rating-list">
              <button
                className="review-rating review-rating-hard"
                type="button"
                onClick={() => submitRating("hard")}
              >
                어려움 · 내일
                <span className="review-rating-note">내일 다시</span>
              </button>
              <button
                className="review-rating review-rating-good"
                type="button"
                onClick={() => submitRating("good")}
              >
                기억남 · {card.current_interval_days * 2}일 후
                <span className="review-rating-note">N×2일 후</span>
              </button>
              <button
                className="review-rating review-rating-easy"
                type="button"
                onClick={() => submitRating("easy")}
              >
                완벽! · {card.current_interval_days * 3}일 후
                <span className="review-rating-note">N×3일 후</span>
              </button>
            </div>
          </>
        )}

        {toast ? (
          <p className="review-toast" role="status">
            {toast}
          </p>
        ) : null}
      </main>

      {confirmExit ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-label="복습 종료 확인"
          >
            <p className="confirm-title">복습을 끝낼까요?</p>
            <p className="confirm-copy">
              이미 평가한 카드는 저장됐어요. 남은 카드는 다음에 이어서 할 수
              있어요
            </p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                onClick={() => setConfirmExit(false)}
              >
                계속하기
              </button>
              <button
                className="confirm-destructive"
                type="button"
                onClick={() => router.push("/home")}
              >
                나가기
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

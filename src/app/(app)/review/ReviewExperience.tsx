"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { CloseIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import { type ReviewRating, useReview } from "./useReview";

// S10 복습 화면.
// 1단계: 한국어 상황만 노출(active recall) → '정답 보기'.
// 2단계: 영어 표현 카드 + 평가 3버튼이 250ms 슬라이드업으로 함께 노출.
// 평가 제출: POST /api/review/{id}/submit { rating } → 서버가 산정한 next_interval_days로 토스트.
// 서버 즉시 반영 후 다음 카드로 진행. 로드된 배열 소진 시 exclude_ids로 다음 배치 조회.

const TOAST_MS = 2000;

export function ReviewExperience() {
  const router = useRouter();
  const {
    cards,
    totalDue,
    loadingInitial,
    errorInitial,
    retryInitial,
    fetchNextBatch,
    submit,
  } = useReview();

  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [doneCount, setDoneCount] = useState(0);
  const [toast, setToast] = useState<string | null>(null);
  const [confirmExit, setConfirmExit] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(false);
  const [pendingRating, setPendingRating] = useState<ReviewRating | null>(null);
  const [fetchingNext, setFetchingNext] = useState(false);
  const [nextBatchError, setNextBatchError] = useState(false);
  const [completed, setCompleted] = useState(false);

  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (toastTimerRef.current) {
        clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

  const showToast = useCallback((rating: ReviewRating, nextDays: number) => {
    const message =
      rating === "hard"
        ? "📅 내일 다시 만나요"
        : rating === "good"
          ? `📅 ${nextDays}일 후 다시`
          : `🎉 ${nextDays}일 후 다시!`;
    setToast(message);
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = setTimeout(() => setToast(null), TOAST_MS);
  }, []);

  const card = index < cards.length ? cards[index] : null;

  // 평가 제출 후 다음 카드로 진행. 로드된 배열을 소진했으면 다음 배치를 가져온다.
  // 다음 배치가 비어 있으면 완료 상태로 전환한다.
  const advance = useCallback(
    async (nextIndex: number) => {
      if (nextIndex < cards.length) {
        setIndex(nextIndex);
        setRevealed(false);
        return;
      }
      setFetchingNext(true);
      setNextBatchError(false);
      try {
        const fresh = await fetchNextBatch();
        if (fresh > 0) {
          setIndex(nextIndex);
          setRevealed(false);
        } else {
          setCompleted(true);
        }
      } catch {
        setNextBatchError(true);
      } finally {
        setFetchingNext(false);
      }
    },
    [cards.length, fetchNextBatch],
  );

  const submitRating = useCallback(
    async (rating: ReviewRating) => {
      if (!card || submitting) {
        return;
      }
      setSubmitting(true);
      setSubmitError(false);
      setPendingRating(rating);
      try {
        const result = await submit(card.id, rating);
        setDoneCount((count) => count + 1);
        showToast(rating, result.next_interval_days);
        setPendingRating(null);
        await advance(index + 1);
      } catch {
        // 제출 네트워크 오류: 카드 비전진, 상태 변경 없음. 재시도 CTA 노출.
        setSubmitError(true);
      } finally {
        setSubmitting(false);
      }
    },
    [advance, card, index, showToast, submit, submitting],
  );

  const retryNextBatch = useCallback(() => {
    void advance(index + 1);
  }, [advance, index]);

  // ---- 로딩 첫 배치 ----
  if (loadingInitial) {
    return (
      <div className="app-screen review-screen">
        <header className="review-topbar">
          <span className="icon-button" aria-hidden="true" />
        </header>
        <div className="review-progress-track" aria-hidden="true">
          <span className="review-progress-fill" style={{ width: "0%" }} />
        </div>
        <main className="review-main" aria-busy="true">
          <p className="review-caption">복습 카드를 불러오는 중…</p>
          <div className="review-question-card skeleton-card">
            <span className="skeleton skeleton-line wide" style={{ width: "70%" }} />
          </div>
        </main>
      </div>
    );
  }

  // ---- 첫 배치 로드 실패 ----
  if (errorInitial) {
    return (
      <div className="app-screen review-screen">
        <main className="review-complete">
          <p className="review-complete-emoji" aria-hidden="true">
            😵
          </p>
          <h1 className="review-complete-title">불러오지 못했어요</h1>
          <p className="review-complete-sub">
            연결이 불안정해요. 다시 시도해주세요.
          </p>
          <button
            className="primary-button"
            type="button"
            onClick={retryInitial}
          >
            다시 시도
          </button>
        </main>
      </div>
    );
  }

  // ---- 빈 큐(처음부터 복습할 카드 0) — 완료 상태와 구분 ----
  if (totalDue === 0 && !completed) {
    return (
      <div className="app-screen review-screen">
        <main className="review-complete">
          <p className="review-complete-emoji" aria-hidden="true">
            📭
          </p>
          <h1 className="review-complete-title">오늘 복습할 카드가 없어요</h1>
          <p className="review-complete-sub">
            새 표현을 저장하면 복습 큐에 쌓여요.
          </p>
          <button
            className="primary-button"
            type="button"
            onClick={() => router.push("/library")}
          >
            내 기록 보기
          </button>
        </main>
      </div>
    );
  }

  // ---- 완료 ----
  if (completed || card === null) {
    return (
      <div className="app-screen review-screen">
        <main className="review-complete">
          <p className="review-complete-emoji" aria-hidden="true">
            🎉
          </p>
          <h1 className="review-complete-title">
            오늘 <CountUp value={doneCount} />개 복습 완료
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

  const progressLabel = `${Math.min(index + 1, totalDue)} / ${totalDue}`;

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
        <span className="review-progress-label">{progressLabel}</span>
      </header>
      <div
        className="review-progress-track"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={totalDue}
        aria-valuenow={index}
      >
        <span
          className="review-progress-fill"
          style={{ width: `${(index / totalDue) * 100}%` }}
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
            <div className="review-reveal-group">
              <div className="review-answer-card">
                <div className="detail-variant-pron-row">
                  <p className="review-answer-english">
                    {card.variant.english_text}
                  </p>
                  <PlayButton label={card.variant.english_text} />
                </div>
                {card.variant.ipa || card.variant.korean_pronunciation ? (
                  <p className="expression-pron">
                    {[card.variant.ipa, card.variant.korean_pronunciation]
                      .filter(Boolean)
                      .join(" · ")}
                  </p>
                ) : null}
                {card.variant.pronunciation_tip ? (
                  <p className="review-answer-tip">
                    🗣 {card.variant.pronunciation_tip}
                  </p>
                ) : null}
                {card.variant.cultural_tip ? (
                  <p className="review-answer-tip">
                    💡 {card.variant.cultural_tip}
                  </p>
                ) : null}
              </div>

              <p className="review-rate-caption">기억하셨나요?</p>
              <div className="review-rating-list">
                <button
                  className="review-rating review-rating-hard"
                  type="button"
                  disabled={submitting}
                  aria-busy={submitting}
                  onClick={() => void submitRating("hard")}
                >
                  어려움
                  <span className="review-rating-note">내일 다시</span>
                </button>
                <button
                  className="review-rating review-rating-good"
                  type="button"
                  disabled={submitting}
                  aria-busy={submitting}
                  onClick={() => void submitRating("good")}
                >
                  기억남
                  <span className="review-rating-note">
                    {card.current_interval_days * 2}일 후
                  </span>
                </button>
                <button
                  className="review-rating review-rating-easy"
                  type="button"
                  disabled={submitting}
                  aria-busy={submitting}
                  onClick={() => void submitRating("easy")}
                >
                  완벽!
                  <span className="review-rating-note">
                    {card.current_interval_days * 3}일 후
                  </span>
                </button>
              </div>
            </div>
          </>
        )}

        {submitError ? (
          <div className="review-submit-error" role="alert">
            <p className="review-submit-error-text">
              저장에 실패했어요. 다시 시도해주세요.
            </p>
            <button
              className="retry-button"
              type="button"
              disabled={submitting}
              onClick={() => {
                if (pendingRating) {
                  void submitRating(pendingRating);
                }
              }}
            >
              다시 시도
            </button>
          </div>
        ) : null}

        {nextBatchError ? (
          <div className="review-submit-error" role="alert">
            <p className="review-submit-error-text">
              다음 카드를 불러오지 못했어요.
            </p>
            <button
              className="retry-button"
              type="button"
              disabled={fetchingNext}
              onClick={retryNextBatch}
            >
              다시 시도
            </button>
          </div>
        ) : null}

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

// 완료 화면의 책장 카운트업. 0 → value로 약 600ms 증가.
function CountUp({ value }: { value: number }) {
  const [display, setDisplay] = useState(0);

  useEffect(() => {
    if (value <= 0) {
      return;
    }
    let raf = 0;
    let start: number | null = null;
    const duration = 600;
    const step = (timestamp: number) => {
      if (start === null) {
        start = timestamp;
      }
      const progress = Math.min((timestamp - start) / duration, 1);
      setDisplay(Math.round(progress * value));
      if (progress < 1) {
        raf = requestAnimationFrame(step);
      }
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [value]);

  return <>{display}</>;
}

"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type {
  ReviewCardDto,
  ReviewTodayResult,
} from "@/lib/review/get-review-today";
import type {
  ReviewRating,
  SubmitRatingResult,
} from "@/lib/review/submit-rating";

// S10 복습 데이터 페칭 훅.
// - 마운트 시 GET /api/review/today?limit=10 으로 첫 배치 로드
// - 로드된 배열을 모두 소진하면 exclude_ids로 다음 배치 조회(중복 방지)
// - 평가는 POST /api/review/{id}/submit 으로 서버에 즉시 반영, 성공 시 next_interval_days 반환
// SWR/react-query 없이 useState + fetch (현 의존성 정책). AbortController로 마운트 해제 경합 방지.

const PAGE_SIZE = 10;

export type { ReviewCardDto, ReviewRating };

async function fetchBatch(
  excludeIds: string[],
  signal: AbortSignal,
): Promise<ReviewTodayResult> {
  const params = new URLSearchParams();
  params.set("limit", String(PAGE_SIZE));
  if (excludeIds.length > 0) {
    params.set("exclude_ids", excludeIds.join(","));
  }
  const res = await fetch(`/api/review/today?${params.toString()}`, { signal });
  if (!res.ok) {
    throw new Error(`review today failed: ${res.status}`);
  }
  return (await res.json()) as ReviewTodayResult;
}

export type UseReviewResult = {
  cards: ReviewCardDto[];
  totalDue: number;
  loadingInitial: boolean;
  errorInitial: boolean;
  /** 다음 배치를 더 가져올 수 없음(서버가 빈 배열 반환) → 완료 판정에 사용. */
  exhausted: boolean;
  retryInitial: () => void;
  fetchNextBatch: () => Promise<number>;
  submit: (
    reviewCardId: string,
    rating: ReviewRating,
  ) => Promise<SubmitRatingResult>;
};

export function useReview(): UseReviewResult {
  const [cards, setCards] = useState<ReviewCardDto[]>([]);
  const [totalDue, setTotalDue] = useState(0);
  const [loadingInitial, setLoadingInitial] = useState(true);
  const [errorInitial, setErrorInitial] = useState(false);
  const [exhausted, setExhausted] = useState(false);

  const initialAbortRef = useRef<AbortController | null>(null);
  const nextAbortRef = useRef<AbortController | null>(null);
  // 이미 로드한 카드 id(다음 배치 exclude용). 최신 cards를 의존성 없이 읽기 위한 ref.
  const loadedIdsRef = useRef<string[]>([]);
  const fetchingNextRef = useRef(false);

  const loadInitial = useCallback(() => {
    initialAbortRef.current?.abort();
    nextAbortRef.current?.abort();
    fetchingNextRef.current = false;

    const controller = new AbortController();
    initialAbortRef.current = controller;

    setLoadingInitial(true);
    setErrorInitial(false);
    setExhausted(false);

    fetchBatch([], controller.signal)
      .then((batch) => {
        if (controller.signal.aborted) {
          return;
        }
        loadedIdsRef.current = batch.cards.map((c) => c.id);
        setCards(batch.cards);
        setTotalDue(batch.total_due);
        setLoadingInitial(false);
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || isAbort(err)) {
          return;
        }
        setErrorInitial(true);
        setLoadingInitial(false);
      });
  }, []);

  // 동기 setState를 마이크로태스크로 미뤄 effect 본문에서 직접 호출하지 않는다(useLibrary와 동일).
  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        loadInitial();
      }
    });
    return () => {
      cancelled = true;
      initialAbortRef.current?.abort();
      nextAbortRef.current?.abort();
    };
  }, [loadInitial]);

  // 로드된 카드를 모두 소진했을 때 호출. 새로 받은 카드 수를 반환(0이면 완료).
  const fetchNextBatch = useCallback(async (): Promise<number> => {
    if (fetchingNextRef.current) {
      return 0;
    }
    fetchingNextRef.current = true;
    const controller = new AbortController();
    nextAbortRef.current = controller;
    try {
      const batch = await fetchBatch(loadedIdsRef.current, controller.signal);
      if (controller.signal.aborted) {
        return 0;
      }
      const fresh = batch.cards.filter(
        (c) => !loadedIdsRef.current.includes(c.id),
      );
      if (fresh.length === 0) {
        setExhausted(true);
        return 0;
      }
      loadedIdsRef.current = [
        ...loadedIdsRef.current,
        ...fresh.map((c) => c.id),
      ];
      setCards((prev) => [...prev, ...fresh]);
      return fresh.length;
    } catch (err: unknown) {
      if (isAbort(err)) {
        return 0;
      }
      throw err;
    } finally {
      fetchingNextRef.current = false;
    }
  }, []);

  const submit = useCallback(
    async (
      reviewCardId: string,
      rating: ReviewRating,
    ): Promise<SubmitRatingResult> => {
      const res = await fetch(
        `/api/review/${encodeURIComponent(reviewCardId)}/submit`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ rating }),
        },
      );
      if (!res.ok) {
        throw new Error(`submit failed: ${res.status}`);
      }
      return (await res.json()) as SubmitRatingResult;
    },
    [],
  );

  return {
    cards,
    totalDue,
    loadingInitial,
    errorInitial,
    exhausted,
    retryInitial: loadInitial,
    fetchNextBatch,
    submit,
  };
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

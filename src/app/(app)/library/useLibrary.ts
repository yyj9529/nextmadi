"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { ExpressionListItem } from "@/lib/expression/list-expressions";
import { isCurrentLibraryRequest } from "@/lib/library-request-generation";

// S08 라이브러리 데이터 페칭 훅.
// - 검색어 300ms 디바운스 → q 바뀌면 리스트 리셋 후 재조회
// - cursor 기반 무한 스크롤(append, id 중복 제거)
// - 초기/추가 로딩·에러 상태 분리(전체 재시도 vs 하단 인라인 재시도)
// SWR/react-query 없이 useState + fetch (현 의존성 정책).

const DEBOUNCE_MS = 300;
const PAGE_SIZE = 20;

export type LibraryState = {
  items: ExpressionListItem[];
  /** 디바운스가 반영된, 실제 조회에 쓰인 검색어. */
  activeQuery: string;
  loadingInitial: boolean;
  loadingMore: boolean;
  errorInitial: boolean;
  errorMore: boolean;
  hasMore: boolean;
};

type Fetched = {
  items: ExpressionListItem[];
  next_cursor: string | null;
};

async function fetchPage(
  q: string,
  cursor: string | null,
  signal: AbortSignal,
): Promise<Fetched> {
  const params = new URLSearchParams();
  params.set("limit", String(PAGE_SIZE));
  if (q) {
    params.set("q", q);
  }
  if (cursor) {
    params.set("cursor", cursor);
  }
  const res = await fetch(`/api/expressions?${params.toString()}`, {
    signal,
  });
  if (!res.ok) {
    throw new Error(`list failed: ${res.status}`);
  }
  return (await res.json()) as Fetched;
}

export function useLibrary(initialQuery: string) {
  const [keyword, setKeyword] = useState(initialQuery);
  const [activeQuery, setActiveQuery] = useState(initialQuery);

  const [items, setItems] = useState<ExpressionListItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);

  const [loadingInitial, setLoadingInitial] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [errorInitial, setErrorInitial] = useState(false);
  const [errorMore, setErrorMore] = useState(false);

  // 진행 중인 첫-페이지 요청 취소용(검색어 빠르게 바뀔 때 경합 방지).
  const initialAbortRef = useRef<AbortController | null>(null);
  // 진행 중인 추가 페이지 요청 취소용(검색어 변경 중 stale append 방지).
  const moreAbortRef = useRef<AbortController | null>(null);
  const requestGenerationRef = useRef(0);
  // 페이지네이션 동시 호출 가드(IntersectionObserver 연속 발화).
  const loadingMoreRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        setKeyword(initialQuery);
        setActiveQuery(initialQuery);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [initialQuery]);

  // 검색어 디바운스 → activeQuery 확정.
  useEffect(() => {
    if (keyword === activeQuery) {
      return;
    }
    const timer = setTimeout(() => {
      setActiveQuery(keyword);
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keyword, activeQuery]);

  const loadInitial = useCallback((q: string) => {
    const requestGeneration = requestGenerationRef.current + 1;
    requestGenerationRef.current = requestGeneration;

    initialAbortRef.current?.abort();
    moreAbortRef.current?.abort();
    loadingMoreRef.current = false;

    const controller = new AbortController();
    initialAbortRef.current = controller;

    setLoadingInitial(true);
    setLoadingMore(false);
    setErrorInitial(false);
    setErrorMore(false);

    fetchPage(q, null, controller.signal)
      .then((page) => {
        if (
          controller.signal.aborted ||
          !isCurrentLibraryRequest(
            requestGeneration,
            requestGenerationRef.current,
          )
        ) {
          return;
        }
        setItems(page.items);
        setCursor(page.next_cursor);
        setHasMore(page.next_cursor !== null);
        setLoadingInitial(false);
      })
      .catch((err: unknown) => {
        if (
          controller.signal.aborted ||
          isAbort(err) ||
          !isCurrentLibraryRequest(
            requestGeneration,
            requestGenerationRef.current,
          )
        ) {
          return;
        }
        setErrorInitial(true);
        setLoadingInitial(false);
      });
  }, []);

  // activeQuery가 바뀔 때마다(최초 포함) 리스트 리셋 후 첫 페이지 로드.
  // 동기 setState는 마이크로태스크로 미뤄 effect 본문에서 직접 호출하지 않는다.
  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        loadInitial(activeQuery);
      }
    });
    return () => {
      cancelled = true;
      initialAbortRef.current?.abort();
      moreAbortRef.current?.abort();
    };
  }, [activeQuery, loadInitial]);

  const loadMore = useCallback(() => {
    if (loadingMoreRef.current || !hasMore || cursor === null) {
      return;
    }
    loadingMoreRef.current = true;
    setLoadingMore(true);
    setErrorMore(false);

    const requestGeneration = requestGenerationRef.current;
    const controller = new AbortController();
    moreAbortRef.current = controller;
    fetchPage(activeQuery, cursor, controller.signal)
      .then((page) => {
        if (
          controller.signal.aborted ||
          !isCurrentLibraryRequest(
            requestGeneration,
            requestGenerationRef.current,
          )
        ) {
          return;
        }
        setItems((prev) => {
          const seen = new Set(prev.map((it) => it.id));
          const next = page.items.filter((it) => !seen.has(it.id));
          return [...prev, ...next];
        });
        setCursor(page.next_cursor);
        setHasMore(page.next_cursor !== null);
        setLoadingMore(false);
        loadingMoreRef.current = false;
        moreAbortRef.current = null;
      })
      .catch((err: unknown) => {
        if (
          isAbort(err) ||
          !isCurrentLibraryRequest(
            requestGeneration,
            requestGenerationRef.current,
          )
        ) {
          return;
        }
        setErrorMore(true);
        setLoadingMore(false);
        loadingMoreRef.current = false;
        moreAbortRef.current = null;
      });
  }, [activeQuery, cursor, hasMore]);

  const retryInitial = useCallback(() => {
    loadInitial(activeQuery);
  }, [activeQuery, loadInitial]);

  const clearSearch = useCallback(() => {
    setKeyword("");
    setActiveQuery("");
  }, []);

  const state: LibraryState = {
    items,
    activeQuery,
    loadingInitial,
    loadingMore,
    errorInitial,
    errorMore,
    hasMore,
  };

  return {
    keyword,
    setKeyword,
    clearSearch,
    loadMore,
    retryInitial,
    retryMore: loadMore,
    ...state,
  };
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

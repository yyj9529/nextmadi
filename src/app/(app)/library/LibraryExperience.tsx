"use client";

import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";

import { SearchIcon } from "@/components/app/icons";
import { formatRelativeTime } from "@/lib/relative-time";
import { getVirtualWindow } from "@/lib/virtual-window";
import { useLibrary } from "./useLibrary";

// S08 내 기록 (라이브러리). GET /expressions 실데이터 연결.
// - 검색어 300ms 디바운스 + URL 쿼리(?q=) 동기화 → S09 복귀 시 검색 상태 복원
// - cursor 무한 스크롤(IntersectionObserver) + 500개 이상도 버티는 고정행 windowing
// - 헤더 "📚 N권"은 대시보드 집계 카운트 재사용(/api/expressions/count)

const SITUATION_MAX = 80;
const ENGLISH_MAX = 80;
const VIRTUAL_ROW_HEIGHT = 96;
const VIRTUAL_ROW_GAP = 10;
const VIRTUAL_DESKTOP_ROW_GAP = 12;
const VIRTUAL_OVERSCAN_ROWS = 3;

function truncate(text: string, max: number): string {
  if (text.length <= max) {
    return text;
  }
  return `${text.slice(0, max).trimEnd()}…`;
}

export function LibraryExperience() {
  return (
    <Suspense fallback={<LibraryHeaderOnly />}>
      <LibraryContent />
    </Suspense>
  );
}

// useSearchParams는 Suspense 경계가 필요하다. 폴백은 헤더만 그린다.
function LibraryHeaderOnly() {
  return (
    <header className="app-topbar">
      <h1 className="app-logo">내 기록</h1>
      <span className="library-count">📚</span>
    </header>
  );
}

function LibraryContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const initialQuery = searchParams.get("q") ?? "";

  const {
    keyword,
    setKeyword,
    clearSearch,
    loadMore,
    retryInitial,
    retryMore,
    items,
    activeQuery,
    loadingInitial,
    loadingMore,
    errorInitial,
    errorMore,
    hasMore,
  } = useLibrary(initialQuery);

  const [count, setCount] = useState<number | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const columnCount = useLibraryColumnCount();
  const { listRef, virtualWindow } = useVirtualLibraryWindow(
    items.length,
    columnCount,
  );
  const visibleItems = useMemo(
    () => items.slice(virtualWindow.startIndex, virtualWindow.endIndex),
    [items, virtualWindow.startIndex, virtualWindow.endIndex],
  );

  // 헤더 북카운트 — 실패해도 페이지를 막지 않고 숫자만 숨긴다.
  useEffect(() => {
    let cancelled = false;
    fetch("/api/expressions/count")
      .then((res) => (res.ok ? res.json() : null))
      .then((body: { count?: number } | null) => {
        if (!cancelled && body && typeof body.count === "number") {
          setCount(body.count);
        }
      })
      .catch(() => {
        /* 무시: 헤더 숫자는 보조 정보 */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 검색어(디바운스 확정값) → URL 쿼리 동기화. 뒤로가기 복원용.
  useEffect(() => {
    const target = activeQuery ? `/library?q=${encodeURIComponent(activeQuery)}` : "/library";
    router.replace(target, { scroll: false });
  }, [activeQuery, router]);

  // 무한 스크롤: sentinel이 보이면 다음 페이지.
  useEffect(() => {
    const node = sentinelRef.current;
    if (!node || !hasMore || errorMore) {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          loadMore();
        }
      },
      { rootMargin: "200px" },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [hasMore, errorMore, loadMore, items.length]);

  return (
    <>
      <header className="app-topbar">
        <h1 className="app-logo">내 기록</h1>
        <span className="library-count">
          📚{count !== null ? ` ${count}권` : ""}
        </span>
      </header>

      <div className="library-search" role="search">
        {/* 검색 중: 디바운스 대기 또는 검색어가 있는 상태의 조회 진행. */}
        {keyword !== activeQuery ||
        (loadingInitial && activeQuery !== "") ? (
          <span
            className="loading-spinner library-spinner-sm"
            role="status"
            aria-label="검색 중"
          />
        ) : (
          <SearchIcon />
        )}
        <input
          className="library-search-input"
          type="search"
          placeholder="한국어 또는 영어로 검색"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
      </div>

      {renderBody()}
    </>
  );

  function renderBody() {
    // 초기 로딩 — 스켈레톤 5개.
    if (loadingInitial) {
      return <LibrarySkeletons />;
    }

    // 초기 로드 실패 — 풀스크린 재시도.
    if (errorInitial) {
      return (
        <div className="library-empty">
          <p className="library-empty-title">목록을 불러오지 못했어요.</p>
          <button
            className="primary-button library-retry"
            type="button"
            onClick={retryInitial}
          >
            다시 시도
          </button>
        </div>
      );
    }

    // 결과 없음 — 검색 중이면 검색 빈 상태, 아니면 라이브러리 빈 상태.
    if (items.length === 0) {
      if (activeQuery) {
        return (
          <div className="library-empty">
            <p className="library-empty-title">
              ‘{activeQuery}’에 대한 결과가 없어요
            </p>
            <button
              className="library-empty-clear"
              type="button"
              onClick={clearSearch}
            >
              검색어 지우기
            </button>
          </div>
        );
      }
      return (
        <div className="library-empty">
          <p className="library-empty-icon" aria-hidden>
            📚
          </p>
          <p className="library-empty-title">아직 저장한 표현이 없어요</p>
          <p className="library-empty-sub">분석 결과를 저장하면 여기에 모여요</p>
          <Link className="primary-button library-retry" href="/home">
            지금 분석하기
          </Link>
        </div>
      );
    }

    // 결과 목록.
    return (
      <>
        <div
          ref={listRef}
          className="library-virtual-viewport"
          style={{ height: virtualWindow.totalHeight }}
        >
          <ul
            className="library-list library-list-virtual"
            style={{ transform: `translateY(${virtualWindow.offsetY}px)` }}
          >
            {visibleItems.map((item) => (
              <li key={item.id}>
                <Link className="library-card" href={`/expression/${item.id}`}>
                  <span className="library-card-situation">
                    “{truncate(item.original_situation, SITUATION_MAX)}”
                  </span>
                  <span className="library-card-english">
                    {truncate(item.english_text, ENGLISH_MAX)}
                  </span>
                  <span className="library-card-meta">
                    {item.tone_label ? (
                      <span className="library-card-tone">
                        {item.tone_label}
                      </span>
                    ) : null}
                    <span>{formatRelativeTime(item.created_at)}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>

        {/* 페이지네이션 영역 */}
        {errorMore ? (
          <div className="library-more-error">
            <span>더 불러오지 못했어요.</span>
            <button
              className="library-empty-clear"
              type="button"
              onClick={retryMore}
            >
              다시 시도
            </button>
          </div>
        ) : loadingMore ? (
          <div className="library-more-spinner" aria-label="더 불러오는 중">
            <span className="loading-spinner library-spinner-sm" />
          </div>
        ) : hasMore ? (
          <div ref={sentinelRef} className="library-sentinel" aria-hidden />
        ) : (
          <p className="library-end">다 봤어요</p>
        )}
      </>
    );
  }
}

function LibrarySkeletons() {
  return (
    <ul className="library-list" aria-label="불러오는 중">
      {Array.from({ length: 5 }).map((_, i) => (
        <li key={i} className="library-card">
          <span className="skeleton skeleton-line" style={{ width: "40%" }} />
          <span className="skeleton skeleton-line wide" style={{ width: "80%" }} />
          <span className="skeleton skeleton-line" style={{ width: "24%" }} />
        </li>
      ))}
    </ul>
  );
}

function useLibraryColumnCount() {
  const [columnCount, setColumnCount] = useState(1);

  useEffect(() => {
    const update = () => {
      setColumnCount(window.matchMedia("(min-width: 768px)").matches ? 2 : 1);
    };
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, []);

  return columnCount;
}

function useVirtualLibraryWindow(itemCount: number, columnCount: number) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const [viewport, setViewport] = useState({
    viewportTop: 0,
    viewportHeight: 0,
    containerTop: 0,
  });

  const updateViewport = useCallback(() => {
    const node = listRef.current;
    const rect = node?.getBoundingClientRect();
    setViewport({
      viewportTop: window.scrollY,
      viewportHeight: window.innerHeight,
      containerTop: rect ? rect.top + window.scrollY : 0,
    });
  }, []);

  useEffect(() => {
    const frame = window.requestAnimationFrame(updateViewport);
    window.addEventListener("scroll", updateViewport, { passive: true });
    window.addEventListener("resize", updateViewport);

    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener("scroll", updateViewport);
      window.removeEventListener("resize", updateViewport);
    };
  }, [itemCount, columnCount, updateViewport]);

  return {
    listRef,
    virtualWindow: getVirtualWindow({
      itemCount,
      columnCount,
      viewportTop: viewport.viewportTop,
      viewportHeight: viewport.viewportHeight || 800,
      containerTop: viewport.containerTop,
      rowHeight: VIRTUAL_ROW_HEIGHT,
      rowGap: columnCount > 1 ? VIRTUAL_DESKTOP_ROW_GAP : VIRTUAL_ROW_GAP,
      overscanRows: VIRTUAL_OVERSCAN_ROWS,
    }),
  };
}

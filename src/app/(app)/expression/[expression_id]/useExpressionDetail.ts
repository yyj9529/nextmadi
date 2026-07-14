"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { ExpressionDetail } from "@/lib/expression/get-expression";

// S09 표현 상세 데이터 페칭 훅. (#47)
// - 마운트 시 GET /api/expressions/{id} 1회 조회
// - loading / loaded / notFound(404) / error 상태 분리
// - notFound는 페이지에서 2초 후 /library 리다이렉트에 쓰인다(s09.md UI states)
// - retry로 에러 상태에서 재조회
// SWR/react-query 없이 useState + fetch(현 의존성 정책, useLibrary와 동일).

export type DetailStatus = "loading" | "loaded" | "notFound" | "error";

export type ExpressionDetailState = {
  status: DetailStatus;
  expression: ExpressionDetail | null;
  retry: () => void;
  /** 큐 토글/삭제 후 클라이언트 상태를 로컬로 반영(재조회 없이). */
  setExpression: (next: ExpressionDetail) => void;
};

async function fetchDetail(
  expressionId: string,
  signal: AbortSignal,
): Promise<{ ok: true; data: ExpressionDetail } | { ok: false; notFound: boolean }> {
  const res = await fetch(`/api/expressions/${encodeURIComponent(expressionId)}`, {
    signal,
  });
  if (res.ok) {
    return { ok: true, data: (await res.json()) as ExpressionDetail };
  }
  return { ok: false, notFound: res.status === 404 };
}

export function useExpressionDetail(expressionId: string): ExpressionDetailState {
  const [status, setStatus] = useState<DetailStatus>("loading");
  const [expression, setExpression] = useState<ExpressionDetail | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setStatus("loading");

    fetchDetail(expressionId, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return;
        }
        if (result.ok) {
          setExpression(result.data);
          setStatus("loaded");
        } else {
          setStatus(result.notFound ? "notFound" : "error");
        }
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || isAbort(err)) {
          return;
        }
        setStatus("error");
      });
  }, [expressionId]);

  // 동기 setState(load 내부)를 effect 본문에서 직접 부르지 않고 마이크로태스크로 미룬다
  // (useLibrary와 동일한 패턴, react-hooks/set-state-in-effect 회피).
  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        load();
      }
    });
    return () => {
      cancelled = true;
      abortRef.current?.abort();
    };
  }, [load]);

  return { status, expression, retry: load, setExpression };
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

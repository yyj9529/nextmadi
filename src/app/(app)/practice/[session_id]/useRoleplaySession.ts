"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { RoleplaySession } from "@/lib/practice/types";

// S12 롤플레이 세션 로드 훅. (#61)
// - 마운트 시 GET /api/practice/sessions/{id} 1회 조회로 대화 상태 복원
// - loading / loaded / notFound(404) / error 상태 분리
// - notFound는 페이지에서 홈으로 복귀에 쓴다(s12.md edge case)
// - retry로 에러 상태에서 재조회
// SWR/react-query 없이 useState + fetch(현 의존성 정책, useExpressionDetail과 동일).

export type SessionStatus = "loading" | "loaded" | "notFound" | "error";

export type RoleplaySessionState = {
  status: SessionStatus;
  session: RoleplaySession | null;
  retry: () => void;
};

async function fetchSession(
  sessionId: string,
  signal: AbortSignal,
): Promise<{ ok: true; data: RoleplaySession } | { ok: false; notFound: boolean }> {
  const res = await fetch(
    `/api/practice/sessions/${encodeURIComponent(sessionId)}`,
    { signal },
  );
  if (res.ok) {
    return { ok: true, data: (await res.json()) as RoleplaySession };
  }
  return { ok: false, notFound: res.status === 404 };
}

export function useRoleplaySession(sessionId: string): RoleplaySessionState {
  const [status, setStatus] = useState<SessionStatus>("loading");
  const [session, setSession] = useState<RoleplaySession | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setStatus("loading");

    fetchSession(sessionId, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return;
        }
        if (result.ok) {
          setSession(result.data);
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
  }, [sessionId]);

  // 동기 setState(load 내부)를 effect 본문에서 직접 부르지 않고 마이크로태스크로 미룬다
  // (useExpressionDetail과 동일한 패턴, react-hooks/set-state-in-effect 회피).
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

  return { status, session, retry: load };
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

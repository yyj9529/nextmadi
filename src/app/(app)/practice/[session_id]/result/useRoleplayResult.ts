"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { RoleplayResult, RoleplaySession } from "@/lib/practice/types";

// S12b 롤플레이 결과 로드 훅. (#63, S12b · 백엔드 #62)
//
// 진입 시 GET /api/practice/sessions/{id}로 세션을 조회하고 상태에 따라 분기한다(s12b.md AC):
// - abandoned  → 결과 생성하지 않고 stub 상태(AC4)
// - active     → 아직 안 끝난 연습, 활성 세션으로 복귀 신호(AC3)
// - completed  → result_json이 있으면 즉시 렌더, null이면 POST /result로 1회 생성(AC1)
// 생성 실패/타임아웃은 error 상태 + retry로 재생성(edge case: 부분 캐시 없음, 매번 처음부터).
// SWR/react-query 없이 useState + fetch(useRoleplaySession과 동일 의존성 정책).

export type ResultStatus =
  | "loading"
  | "generating"
  | "loaded"
  | "abandoned"
  | "notCompleted"
  | "notFound"
  | "error";

export type RoleplayResultState = {
  status: ResultStatus;
  /** loaded일 때만 채워진다. */
  result: RoleplayResult | null;
  /** "원본 표현 보기" 링크용. soft-delete 시 백엔드가 null로 내려 링크를 숨긴다(AC3-3). */
  expressionId: string | null;
  retry: () => void;
};

async function fetchSession(
  sessionId: string,
  signal: AbortSignal,
): Promise<
  | { ok: true; data: RoleplaySession }
  | { ok: false; notFound: boolean }
> {
  const res = await fetch(
    `/api/practice/sessions/${encodeURIComponent(sessionId)}`,
    { signal },
  );
  if (res.ok) {
    return { ok: true, data: (await res.json()) as RoleplaySession };
  }
  return { ok: false, notFound: res.status === 404 };
}

type GenerateOutcome =
  | { kind: "loaded"; result: RoleplayResult }
  | { kind: "notCompleted" }
  | { kind: "notFound" }
  | { kind: "error" };

async function generateResult(
  sessionId: string,
  signal: AbortSignal,
): Promise<GenerateOutcome> {
  const res = await fetch(
    `/api/practice/sessions/${encodeURIComponent(sessionId)}/result`,
    { method: "POST", signal },
  );
  if (res.ok) {
    return { kind: "loaded", result: (await res.json()) as RoleplayResult };
  }
  if (res.status === 409) {
    return { kind: "notCompleted" };
  }
  if (res.status === 404) {
    return { kind: "notFound" };
  }
  return { kind: "error" };
}

export function useRoleplayResult(sessionId: string): RoleplayResultState {
  const [status, setStatus] = useState<ResultStatus>("loading");
  const [result, setResult] = useState<RoleplayResult | null>(null);
  const [expressionId, setExpressionId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const { signal } = controller;

    setStatus("loading");

    void (async () => {
      try {
        const session = await fetchSession(sessionId, signal);
        if (signal.aborted) {
          return;
        }
        if (!session.ok) {
          setStatus(session.notFound ? "notFound" : "error");
          return;
        }

        setExpressionId(session.data.expression_id ?? null);

        if (session.data.status === "abandoned") {
          setStatus("abandoned");
          return;
        }
        if (session.data.status === "active") {
          setStatus("notCompleted");
          return;
        }

        // completed
        if (session.data.result_json) {
          setResult(session.data.result_json);
          setStatus("loaded");
          return;
        }

        setStatus("generating");
        const outcome = await generateResult(sessionId, signal);
        if (signal.aborted) {
          return;
        }
        if (outcome.kind === "loaded") {
          setResult(outcome.result);
          setStatus("loaded");
        } else if (outcome.kind === "notCompleted") {
          setStatus("notCompleted");
        } else if (outcome.kind === "notFound") {
          setStatus("notFound");
        } else {
          setStatus("error");
        }
      } catch (err: unknown) {
        if (signal.aborted || isAbort(err)) {
          return;
        }
        setStatus("error");
      }
    })();
  }, [sessionId]);

  // 동기 setState를 effect 본문에서 직접 부르지 않고 마이크로태스크로 미룬다
  // (useRoleplaySession과 동일 패턴, react-hooks/set-state-in-effect 회피).
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

  return { status, result, expressionId, retry: load };
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

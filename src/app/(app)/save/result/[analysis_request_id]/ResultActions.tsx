"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { RetryIcon } from "@/components/app/icons";
import {
  clearPendingSave,
  readPendingSave,
  writePendingSave,
} from "@/lib/pending-save";

// S07 하단 액션. (#42)
//
// - 비로그인 저장: pending_save를 sessionStorage에 적고 /login으로 이동(가입 후 자동 저장).
// - 로그인 저장: BFF(POST /api/expressions) 호출 → 저장됨 상태.
// - 자동 재개: 로그인/온보딩 후 이 분석의 pending_save가 남아 있으면 탭 없이 자동 저장하고 정리한다.
// - claim 실패(404): 사과 문구 + /home CTA.

const DEFAULT_VARIANT_ORDER = 1;

type SaveState = "idle" | "saving" | "saved" | "not_found" | "error";

type ResultActionsProps = {
  analysisRequestId: string;
  isAuthenticated: boolean;
  selectedVariantOrder?: number;
};

export function ResultActions({
  analysisRequestId,
  isAuthenticated,
  selectedVariantOrder = DEFAULT_VARIANT_ORDER,
}: ResultActionsProps) {
  const router = useRouter();
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const autoResumeAttempted = useRef(false);

  const runSave = useCallback(async () => {
    setSaveState("saving");
    try {
      const response = await fetch("/api/expressions", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          analysis_request_id: analysisRequestId,
          selected_variant_order: selectedVariantOrder,
        }),
      });

      if (response.status === 404) {
        setSaveState("not_found");
        return;
      }
      if (!response.ok) {
        setSaveState("error");
        return;
      }

      // 저장 성공(201) 또는 이미 저장됨(200/409 정규화) → 저장됨 상태.
      clearPendingSave();
      setSaveState("saved");
    } catch {
      setSaveState("error");
    }
  }, [analysisRequestId, selectedVariantOrder]);

  // 로그인 후 자동 재개: 이 분석에 대한 pending_save가 있으면 탭 없이 한 번 저장한다.
  useEffect(() => {
    if (!isAuthenticated || autoResumeAttempted.current) {
      return;
    }
    const pending = readPendingSave();
    if (pending && pending.analysisRequestId === analysisRequestId) {
      autoResumeAttempted.current = true;
      // 비동기 저장은 마이크로태스크로 미뤄 effect 본문에서 동기 setState를 호출하지 않는다.
      queueMicrotask(() => {
        void runSave();
      });
    }
  }, [isAuthenticated, analysisRequestId, runSave]);

  const handleSave = () => {
    if (saveState === "saving" || saveState === "saved") {
      return;
    }
    if (!isAuthenticated) {
      // 가입 전: 의도를 보존하고 로그인으로. 가입+온보딩 후 자동 저장된다.
      writePendingSave({ analysisRequestId, selectedVariantOrder });
      router.push("/login");
      return;
    }
    void runSave();
  };

  if (saveState === "not_found") {
    return (
      <div className="result-actions result-actions-error" role="alert">
        <p className="save-error-copy">
          저장할 분석을 찾지 못했어요. 시간이 지나 만료되었을 수 있어요.
        </p>
        <Link className="primary-button" href="/home">
          홈으로
        </Link>
      </div>
    );
  }

  return (
    <div className="result-actions">
      <button
        className="save-button"
        type="button"
        disabled={saveState === "saving" || saveState === "saved"}
        aria-busy={saveState === "saving"}
        onClick={handleSave}
      >
        {saveState === "saved"
          ? "저장됨 ✓"
          : saveState === "saving"
            ? "저장 중..."
            : "저장하기"}
      </button>
      {saveState === "saved" ? (
        <p className="save-toast" role="status">
          📚 책장에 추가했어요
        </p>
      ) : null}
      {saveState === "error" ? (
        <p className="save-toast" role="alert">
          저장에 실패했어요. 다시 시도해주세요.
        </p>
      ) : null}
      <button
        className="retry-button"
        type="button"
        onClick={() => router.push("/home")}
      >
        <RetryIcon /> 다시
      </button>
    </div>
  );
}

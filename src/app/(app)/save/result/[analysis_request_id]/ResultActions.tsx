"use client";

import { useRouter } from "next/navigation";

import { RetryIcon } from "@/components/app/icons";
import { MOCK_EXPRESSION_ID } from "@/lib/mock-api";

// S07 하단 액션.
// 저장하기: POST /expressions { analysis_request_id } → 201 → 표현 상세.
// 목 패스: 고정 표현(mock-expression-1) 상세로 라우팅.
// 다시: 입력 화면(S04)으로 복귀.
export function ResultActions() {
  const router = useRouter();

  return (
    <div className="result-actions">
      <button
        className="save-button"
        type="button"
        onClick={() => router.push(`/expression/${MOCK_EXPRESSION_ID}`)}
      >
        저장하기
      </button>
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

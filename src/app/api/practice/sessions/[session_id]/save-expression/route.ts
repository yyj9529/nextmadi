import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  SaveRoleplayExpressionError,
  saveRoleplayExpression,
} from "@/lib/practice/save-roleplay-expression";

// S12b 추천 표현 저장 BFF. (#63 · 백엔드 #62)
//
// 추천 표현 카드 "저장" 탭이 호출한다. 라이브러리에 행을 만드는 상태 변경이므로 Origin이 있으면
// 호스트와 일치해야 한다. NextAuth 세션을 서버에서 확인하고 user_id로 내부 인증 토큰을 발급해
// POST /api/v1/practice/sessions/{id}/save-expression 를 프록시한다. 클라이언트가 준
// Idempotency-Key를 백엔드로 전달해 연타/재시도가 중복 저장되지 않게 한다(s12b.md US2 AC4).

type SaveRequestBody = {
  recommended_expression_index?: unknown;
};

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

/** 상태 변경 요청의 CSRF 방어: Origin이 있으면 호스트와 일치해야 한다. */
async function isSameOrigin(): Promise<boolean> {
  const headerStore = await headers();
  const origin = headerStore.get("origin");
  if (!origin) {
    return true;
  }
  const host = headerStore.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

export async function POST(
  request: Request,
  context: { params: Promise<{ session_id: string }> },
): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const { session_id: sessionId } = await context.params;
  if (!sessionId) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  let body: SaveRequestBody;
  try {
    body = (await request.json()) as SaveRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const index = body.recommended_expression_index;
  if (typeof index !== "number" || !Number.isInteger(index) || index < 0) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  // 클라이언트가 준 Idempotency-Key를 백엔드로 전달 — 연타/재시도가 표현을 중복 저장하지 않게 한다.
  const idempotencyKey = (await headers()).get("idempotency-key") ?? undefined;

  try {
    const result = await saveRoleplayExpression(
      { userId, sessionId, recommendedExpressionIndex: index },
      { idempotencyKey },
    );
    return Response.json(result, { status: 200 });
  } catch (error) {
    if (
      error instanceof SaveRoleplayExpressionError &&
      error.isNotFound
    ) {
      return jsonError(404, "not_found", "세션을 찾지 못했어요.");
    }
    return jsonError(
      502,
      "save_failed",
      "저장하지 못했어요. 다시 시도해주세요.",
    );
  }
}

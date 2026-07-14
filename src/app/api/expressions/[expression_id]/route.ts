import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  DeleteExpressionError,
  deleteExpression,
} from "@/lib/expression/delete-expression";
import {
  ExpressionNotFoundError,
  getExpression,
} from "@/lib/expression/get-expression";

// S09 표현 상세/삭제 BFF. (#47)
//
// 브라우저는 Spring Boot를 직접 호출하지 않는다. 이 핸들러가 NextAuth 세션을 서버에서 확인하고
// user_id로 내부 인증 토큰을 발급해 GET/DELETE /api/v1/expressions/{id} 를 프록시한다.
// GET은 읽기 전용이라 Origin 체크 없이 세션만 확인하고, DELETE는 상태 변경이라 Origin을 검사한다.
// 없거나 소유하지 않거나 삭제된 표현은 404로 내려 클라이언트가 /library로 복귀하게 한다.

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

/** 상태 변경 요청의 CSRF 방어: Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). */
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

export async function GET(
  _request: Request,
  context: { params: Promise<{ expression_id: string }> },
): Promise<Response> {
  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const { expression_id: expressionId } = await context.params;
  if (!expressionId) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  try {
    const expression = await getExpression({ userId, expressionId });
    return Response.json(expression, { status: 200 });
  } catch (error) {
    if (error instanceof ExpressionNotFoundError) {
      return jsonError(404, "not_found", "표현을 찾지 못했어요.");
    }
    // 백엔드/네트워크 오류. id/토큰은 로깅하지 않는다.
    return jsonError(
      502,
      "detail_failed",
      "표현을 불러오지 못했어요. 다시 시도해주세요.",
    );
  }
}

export async function DELETE(
  _request: Request,
  context: { params: Promise<{ expression_id: string }> },
): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const { expression_id: expressionId } = await context.params;
  if (!expressionId) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  try {
    await deleteExpression({ userId, expressionId });
    return new Response(null, { status: 204 });
  } catch (error) {
    if (error instanceof DeleteExpressionError && error.isNotFound) {
      return jsonError(404, "not_found", "표현을 찾지 못했어요.");
    }
    return jsonError(502, "delete_failed", "삭제에 실패했어요. 다시 시도해주세요.");
  }
}

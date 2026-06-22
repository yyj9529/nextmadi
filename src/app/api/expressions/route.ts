import { cookies, headers } from "next/headers";

import { auth } from "@/auth";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import {
  SaveExpressionError,
  saveExpression,
} from "@/lib/expression/save-expression";

// S07 저장 BFF 라우트. (#42, ADR-010)
//
// 브라우저는 Spring Boot를 직접 호출하지 않는다. 이 핸들러가 NextAuth 세션을 서버에서 확인하고,
// 가입 전 익명 session_token을 httpOnly 쿠키에서 읽어, user_id로 내부 인증 토큰을 발급한 뒤
// Spring의 POST /api/v1/expressions를 호출한다. claim 실패(404)는 그대로 404로 내려보내
// 클라이언트가 사과 문구 + /home CTA를 보여주게 한다(S07 edge case).

type SaveRequestBody = {
  analysis_request_id?: unknown;
  selected_variant_order?: unknown;
};

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
    // 동일 출처 fetch는 Origin을 생략할 수 있다. Lax 쿠키 + 아래 인증으로 충분.
    return true;
  }
  const host = headerStore.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

export async function POST(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  let body: SaveRequestBody;
  try {
    body = (await request.json()) as SaveRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const analysisRequestId = body.analysis_request_id;
  if (typeof analysisRequestId !== "string" || analysisRequestId.length === 0) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const selectedVariantOrder =
    typeof body.selected_variant_order === "number"
      ? body.selected_variant_order
      : undefined;

  const cookieStore = await cookies();
  const claimSessionToken =
    cookieStore.get(ANON_SESSION_COOKIE)?.value ?? null;

  try {
    const result = await saveExpression({
      userId,
      analysisRequestId,
      selectedVariantOrder,
      claimSessionToken,
    });
    // 409(중복)도 UI는 저장 성공으로 취급하므로 200으로 정규화한다.
    return Response.json(result.expression, {
      status: result.alreadySaved ? 200 : 201,
    });
  } catch (error) {
    if (error instanceof SaveExpressionError && error.isNotFound) {
      return jsonError(
        404,
        "not_found",
        "저장할 분석을 찾지 못했어요. 다시 시도해주세요.",
      );
    }
    // 그 외(백엔드 오류/네트워크)는 일반 오류로. 원문/토큰은 로깅하지 않는다.
    return jsonError(502, "save_failed", "저장에 실패했어요. 다시 시도해주세요.");
  }
}

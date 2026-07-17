import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  GenerateResultError,
  generateResult,
} from "@/lib/practice/generate-result";

// S12b 롤플레이 결과 생성/조회 BFF. (#63 · 백엔드 #62)
//
// 세션 result_json이 null일 때 클라이언트가 호출한다. Sonnet 결과 생성(상태 변경/과금)이므로
// Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). NextAuth 세션을 서버에서 확인하고
// user_id로 내부 인증 토큰을 발급해 POST /api/v1/practice/sessions/{id}/result 를 프록시한다.
// completed가 아니면 409(활성 세션 복귀), 미소유/미발견은 404로 내려 클라이언트가 안내한다.

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
  _request: Request,
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

  try {
    const result = await generateResult({ userId, sessionId });
    return Response.json(result, { status: 200 });
  } catch (error) {
    if (error instanceof GenerateResultError) {
      if (error.isNotFound) {
        return jsonError(404, "not_found", "세션을 찾지 못했어요.");
      }
      if (error.isNotCompleted) {
        return jsonError(409, "not_completed", "아직 끝나지 않은 연습이에요.");
      }
    }
    return jsonError(
      502,
      "result_failed",
      "결과를 만들지 못했어요. 다시 시도해주세요.",
    );
  }
}

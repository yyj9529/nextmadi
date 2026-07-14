import { auth } from "@/auth";
import { SessionNotFoundError, getSession } from "@/lib/practice/get-session";

// S12 롤플레이 세션 조회 BFF. (#61 · 백엔드 #59)
//
// 화면 진입/새로고침/복귀 시 대화 상태를 복원한다. 읽기 전용이라 Origin 체크 없이 세션만
// 확인하고, user_id로 내부 인증 토큰을 발급해 GET /api/v1/practice/sessions/{id} 를 프록시한다.
// 없거나 소유하지 않는 세션은 404로 내려 클라이언트가 홈/라이브러리로 복귀하게 한다.

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

export async function GET(
  _request: Request,
  context: { params: Promise<{ session_id: string }> },
): Promise<Response> {
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
    const state = await getSession({ userId, sessionId });
    return Response.json(state, { status: 200 });
  } catch (error) {
    if (error instanceof SessionNotFoundError) {
      return jsonError(404, "not_found", "세션을 찾지 못했어요.");
    }
    return jsonError(
      502,
      "session_failed",
      "세션을 불러오지 못했어요. 다시 시도해주세요.",
    );
  }
}

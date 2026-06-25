import { auth } from "@/auth";
import { getReviewToday } from "@/lib/review/get-review-today";

// S10 오늘의 복습 큐 조회 BFF. (#50)
//
// 브라우저는 Spring Boot를 직접 호출하지 않는다. 이 핸들러가 NextAuth 세션을 서버에서 확인하고,
// user_id로 내부 인증 토큰을 발급해 GET /api/v1/review/today?limit&exclude_ids 를 프록시한다.
// 읽기 전용이므로 CSRF(Origin) 체크 없이 세션만 확인한다.

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

export async function GET(request: Request): Promise<Response> {
  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const url = new URL(request.url);
  const limitParam = url.searchParams.get("limit");
  const parsedLimit = limitParam !== null ? Number(limitParam) : undefined;
  const limit =
    parsedLimit !== undefined && Number.isFinite(parsedLimit)
      ? parsedLimit
      : undefined;

  const excludeIdsParam = url.searchParams.get("exclude_ids");
  const excludeIds = excludeIdsParam
    ? excludeIdsParam
        .split(",")
        .map((id) => id.trim())
        .filter((id) => id.length > 0)
    : undefined;

  try {
    const result = await getReviewToday({ userId, limit, excludeIds });
    return Response.json(result, { status: 200 });
  } catch {
    // 백엔드/네트워크 오류. 토큰은 로깅하지 않는다.
    return jsonError(
      502,
      "list_failed",
      "복습 목록을 불러오지 못했어요. 다시 시도해주세요.",
    );
  }
}

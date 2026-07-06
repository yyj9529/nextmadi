import { auth } from "@/auth";
import { getHomeDashboard } from "@/lib/home/get-home-dashboard";

// S04 홈 대시보드 집계 BFF. (#54, #55)
//
// 브라우저는 Spring Boot를 직접 호출하지 않는다. 이 핸들러가 NextAuth 세션을 서버에서 확인하고,
// user_id로 내부 인증 토큰을 발급해 GET /api/v1/home/dashboard 를 프록시한다.
// 첫 페인트용 읽기 전용 집계이므로 CSRF(Origin) 체크 없이 세션만 확인한다.

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

export async function GET(): Promise<Response> {
  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  try {
    const result = await getHomeDashboard({ userId });
    return Response.json(result, { status: 200 });
  } catch {
    // 백엔드/네트워크 오류. 토큰은 로깅하지 않는다.
    return jsonError(
      502,
      "dashboard_failed",
      "홈 정보를 불러오지 못했어요. 다시 시도해주세요.",
    );
  }
}

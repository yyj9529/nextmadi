import { headers } from "next/headers";

import { auth } from "@/auth";
import { cancelAccountDeletion } from "@/lib/user/account-deletion";

// 계정 삭제 예약 취소 BFF. (#24, S11 User Story 3)
//
// 유예 기간 중 재로그인은 이 경로를 거치지 않는다 — Spring Boot의 로그인 프로비저닝이
// scheduled_deletion_at 을 그 자리에서 지운다. 이 엔드포인트는 이미 세션을 가진 클라이언트가
// 명시적으로 취소를 요청하는 경로다. 예약이 없었으면 no-op이고 그래도 204다.

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

export async function POST(): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  try {
    await cancelAccountDeletion({ userId });
    return new Response(null, { status: 204 });
  } catch {
    return jsonError(
      502,
      "cancel_failed",
      "삭제 예약을 취소하지 못했어요. 다시 시도해주세요.",
    );
  }
}

import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  RemoveFromQueueError,
  removeFromQueue,
} from "@/lib/review/remove-from-queue";

// S09 복습 큐에서 제거 BFF. (#47)
//
// 상태 변경 요청이므로 Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). NextAuth 세션을
// 서버에서 확인하고 user_id로 내부 인증 토큰을 발급해 POST /api/v1/review/{id}/remove-from-queue
// 를 프록시한다. 카드 미발견(404)은 그대로 404로 내려, 다른 탭에서 이미 제거된 경합도 UI가
// 제거된 상태로 정리하게 한다.

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
  context: { params: Promise<{ review_card_id: string }> },
): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const { review_card_id: reviewCardId } = await context.params;
  if (!reviewCardId) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  try {
    await removeFromQueue({ userId, reviewCardId });
    return new Response(null, { status: 204 });
  } catch (error) {
    if (error instanceof RemoveFromQueueError && error.isNotFound) {
      return jsonError(404, "not_found", "복습 카드를 찾지 못했어요.");
    }
    return jsonError(
      502,
      "remove_failed",
      "복습 큐에서 제거하지 못했어요. 다시 시도해주세요.",
    );
  }
}

import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  type ReviewRating,
  SubmitRatingError,
  submitRating,
} from "@/lib/review/submit-rating";

// S10 복습 평가 제출 BFF. (#50)
//
// 상태 변경 요청이므로 Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). NextAuth 세션을
// 서버에서 확인하고 user_id로 내부 인증 토큰을 발급해 POST /api/v1/review/{id}/submit 을 프록시한다.
// 카드 미발견(404)은 그대로 404로 내려 클라이언트가 카드를 비전진하고 안내하게 한다.

const VALID_RATINGS: ReadonlySet<string> = new Set(["hard", "good", "easy"]);

type SubmitRequestBody = {
  rating?: unknown;
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

  let body: SubmitRequestBody;
  try {
    body = (await request.json()) as SubmitRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  if (typeof body.rating !== "string" || !VALID_RATINGS.has(body.rating)) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }
  const rating = body.rating as ReviewRating;

  try {
    const result = await submitRating({ userId, reviewCardId, rating });
    return Response.json(result, { status: 200 });
  } catch (error) {
    if (error instanceof SubmitRatingError && error.isNotFound) {
      return jsonError(
        404,
        "not_found",
        "복습 카드를 찾지 못했어요.",
      );
    }
    return jsonError(
      502,
      "submit_failed",
      "평가 저장에 실패했어요. 다시 시도해주세요.",
    );
  }
}

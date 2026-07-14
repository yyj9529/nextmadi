import { headers } from "next/headers";

import { auth } from "@/auth";
import { StartSessionError, startSession } from "@/lib/practice/start-session";

// S12 롤플레이 세션 시작 BFF. (#61 · 백엔드 #59)
//
// S09 "이 표현으로 연습하기"가 호출한다. 상태 변경(세션 생성)이므로 Origin이 있으면 호스트와
// 일치해야 한다(SameSite=Lax 보완). NextAuth 세션을 서버에서 확인하고 user_id로 내부 인증 토큰을
// 발급해 POST /api/v1/practice/sessions 를 프록시한다. 일일 한도(2회) 초과는 429, 표현 미발견은
// 404로 내려 클라이언트가 각각 안내/복귀하게 한다. expression_id는 자격증명이 아니지만 원문은
// 아니므로 로깅하지 않는다(SECURITY.md).

type StartRequestBody = {
  expression_id?: unknown;
  coach_id?: unknown;
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

export async function POST(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  let body: StartRequestBody;
  try {
    body = (await request.json()) as StartRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  if (typeof body.expression_id !== "string" || body.expression_id.length === 0) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }
  const coachId = typeof body.coach_id === "string" ? body.coach_id : undefined;

  // 클라이언트가 준 Idempotency-Key를 백엔드로 전달 — 연타/재시도가 세션(=일일 슬롯)을
  // 중복 생성하지 않게 한다(s12.md US1 AC3). 없으면 lib이 1회용 키를 만든다.
  const idempotencyKey = (await headers()).get("idempotency-key") ?? undefined;

  try {
    const result = await startSession(
      { userId, expressionId: body.expression_id, coachId },
      { idempotencyKey },
    );
    return Response.json(result, { status: 201 });
  } catch (error) {
    if (error instanceof StartSessionError) {
      if (error.isRateLimited) {
        return jsonError(
          429,
          "rate_limit_exceeded",
          "오늘은 2번 다 썼어요. 내일 다시 만나요.",
        );
      }
      if (error.isNotFound) {
        return jsonError(404, "not_found", "표현을 찾지 못했어요.");
      }
    }
    return jsonError(
      502,
      "start_failed",
      "연습을 시작하지 못했어요. 다시 시도해주세요.",
    );
  }
}

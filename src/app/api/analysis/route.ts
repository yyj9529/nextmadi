import { randomUUID } from "node:crypto";

import { cookies, headers } from "next/headers";

import { auth } from "@/auth";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import { checkAnalysisInput, REENTER_MESSAGE } from "@/lib/analysis/input-policy";
import {
  SubmitAnalysisError,
  submitAnalysis,
} from "@/lib/analysis/submit-analysis";

// S02 분석 생성 BFF 라우트. (#35, ADR-010)
//
// 브라우저는 Spring Boot를 직접 호출하지 않는다. 이 핸들러가 NextAuth 세션(user_id) 또는 가입 전
// 익명 session_token을 서버에서 확인해 내부 인증 토큰을 발급하고, Spring의 POST /api/v1/analysis로
// { input_text }를 프록시한다. 익명 첫 방문이면 session_token(UUIDv4)을 새로 만들어 httpOnly
// 쿠키(phraselog_anon_session)로 심는다 — 이후 분석 조회(S07)와 pending-save claim이 같은 토큰을
// 서버에서 읽는다(anon-session.ts). 한도 초과(429)는 클라이언트가 검사하는 rate_limit_exceeded로
// 정규화해 signup CTA 상태를 띄운다(S02 US2).

const MAX_INPUT_LENGTH = 500;

type AnalysisRequestBody = {
  input_text?: unknown;
  landing_example_id?: unknown;
  input_mode?: unknown;
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
    return true;
  }
  const host = headerStore.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** X-Forwarded-For의 첫 IP만 취한다 — Spring은 forwarding chain이 아닌 단일 IP를 기대한다. */
function clientIpFrom(forwardedFor: string | null): string | null {
  if (!forwardedFor) {
    return null;
  }
  const first = forwardedFor.split(",")[0]?.trim();
  return first && first.length > 0 ? first : null;
}

export async function POST(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  let body: AnalysisRequestBody;
  try {
    body = (await request.json()) as AnalysisRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const inputText = body?.input_text;
  if (
    typeof inputText !== "string" ||
    inputText.length === 0 ||
    inputText.length > MAX_INPUT_LENGTH
  ) {
    return jsonError(400, "validation_failed", "입력을 확인해주세요.");
  }

  const inputMode = body.input_mode;
  if (inputMode !== undefined && inputMode !== "expressions" && inputMode !== "word") {
    return jsonError(400, "validation_failed", "입력 목적을 확인해주세요.");
  }
  const check = checkAnalysisInput(inputText);
  if (check === "invalid") return jsonError(400, "invalid_input", REENTER_MESSAGE);
  if (check === "choose_word_intent" && inputMode === undefined) {
    return jsonError(400, "input_choice_required", "단어 뜻이 궁금한지, 할 말을 만들고 싶은지 선택해주세요.");
  }
  const idempotencyKey = request.headers.get("idempotency-key") ?? undefined;
  if (idempotencyKey && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(idempotencyKey)) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const landingExampleId =
    typeof body.landing_example_id === "string" && body.landing_example_id.length > 0
      ? body.landing_example_id
      : undefined;

  // 인증 사용자면 user_id로, 아니면 가입 전 익명 session_token으로 호출한다(XOR 불변식).
  const session = await auth();
  const userId = session?.user?.id;

  const cookieStore = await cookies();
  let sessionToken: string | undefined;
  let mintedSessionToken: string | undefined;
  if (!userId) {
    sessionToken = cookieStore.get(ANON_SESSION_COOKIE)?.value;
    if (!sessionToken) {
      // 첫 방문: 새 익명 토큰을 만들어 응답 쿠키로 심는다. 브라우저 JS가 읽지 못하도록 httpOnly.
      sessionToken = randomUUID();
      mintedSessionToken = sessionToken;
    }
  }

  const headerStore = await headers();
  const clientIp = clientIpFrom(headerStore.get("x-forwarded-for"));

  try {
    const result = await submitAnalysis({
      inputText,
      ...(inputMode ? { inputMode } : {}),
      landingExampleId,
      clientIp,
      ...(userId ? { userId } : { sessionToken }),
    }, { idempotencyKey });

    const response = Response.json(
      { analysis_request_id: result.analysisRequestId },
      { status: 201 },
    );
    if (mintedSessionToken) {
      cookieStore.set(ANON_SESSION_COOKIE, mintedSessionToken, {
        httpOnly: true,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
        path: "/",
      });
    }
    return response;
  } catch (error) {
    if (error instanceof SubmitAnalysisError && error.isRateLimited) {
      // 클라이언트(isRateLimitExceededError)가 검사하는 정확한 코드로 정규화한다.
      return jsonError(
        429,
        "rate_limit_exceeded",
        "오늘 무료 분석을 모두 썼어요.",
      );
    }
    if (error instanceof SubmitAnalysisError && error.status === 400) {
      return jsonError(400, error.errorCode, error.userMessage ?? "입력을 확인해주세요.");
    }
    if (error instanceof SubmitAnalysisError && error.status === 409) {
      return jsonError(409, error.errorCode, "입력이 바뀌었어요. 새 요청으로 다시 제출해주세요.");
    }
    // 그 외(백엔드 오류/네트워크)는 일반 오류로. 원문/토큰은 로깅하지 않는다.
    return jsonError(502, "analysis_failed", "분석에 실패했어요. 다시 시도해주세요.");
  }
}

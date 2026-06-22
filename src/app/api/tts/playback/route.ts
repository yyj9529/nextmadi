import { cookies, headers } from "next/headers";

import { auth } from "@/auth";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import { requestTtsPlayback } from "@/lib/tts/playback";

// S07 카드 TTS 재생 BFF 라우트. (#40, ADR-010)
//
// 브라우저는 Spring Boot를 직접 부르지 않는다. 이 핸들러가 NextAuth 세션(user_id) 또는 가입 전
// 익명 session_token(httpOnly 쿠키)을 서버에서 확인해 내부 인증 토큰을 발급하고, Spring의
// POST /api/v1/tts/playback으로 프록시한다. voice_id는 클라이언트가 정하지 않고 서버에서
// 해석한다 — 동일 텍스트에 동일 voice를 보내야 tts_audio_cache 히트가 안정적이기 때문이다.
//
// 코치별 voice 연동은 후속(#30/코치 API)이며, 그 전까지는 기본 voice로 해석한다. TTS 백엔드(#30)
// 미구현 동안 backend 호출은 실패하고, 아래에서 502로 정규화해 카드가 실패 토스트를 띄운다.

// 기본 TTS voice. 시드 코치(V003)의 tts_voice_id와 같은 형식. 코치 연동 시 세션 코치로 대체.
const DEFAULT_TTS_VOICE_ID =
  process.env.PHRASELOG_DEFAULT_TTS_VOICE_ID ?? "shimmer";

type TtsRequestBody = {
  text?: unknown;
  voice_id?: unknown;
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

export async function POST(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  let body: TtsRequestBody;
  try {
    body = (await request.json()) as TtsRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const text = body.text;
  if (typeof text !== "string" || text.trim().length === 0) {
    return jsonError(400, "validation_failed", "재생할 문장이 없어요.");
  }

  // 인증 사용자면 user_id, 아니면 가입 전 익명 session_token으로 호출한다(XOR 불변식).
  const session = await auth();
  const userId = session?.user?.id;
  const cookieStore = await cookies();
  const sessionToken = cookieStore.get(ANON_SESSION_COOKIE)?.value;

  if (!userId && !sessionToken) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  // 클라이언트가 보낸 voice_id는 신뢰하지 않는다 — 서버에서 해석한 값을 쓴다.
  const voiceId =
    typeof body.voice_id === "string" && body.voice_id.length > 0
      ? body.voice_id
      : DEFAULT_TTS_VOICE_ID;

  try {
    const result = await requestTtsPlayback({
      text,
      voiceId,
      ...(userId ? { userId } : { sessionToken }),
    });
    return Response.json(result, { status: 200 });
  } catch {
    // TTS 백엔드 오류/미구현/네트워크 — 텍스트나 토큰은 로깅하지 않는다(SECURITY.md).
    // 카드는 사용 가능 상태로 남고 클라이언트가 실패 토스트를 띄운다(S07 US2-AC3).
    return jsonError(
      502,
      "tts_failed",
      "음성 재생에 실패했어요. 다시 시도해주세요.",
    );
  }
}

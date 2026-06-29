import { cookies, headers } from "next/headers";

import { auth } from "@/auth";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import { requestTtsPlayback } from "@/lib/tts/playback";

// S07 card TTS playback BFF route (#40, ADR-010).
//
// The browser calls this route instead of Spring directly. The route verifies same-origin state
// changes, derives the internal auth subject from NextAuth or the anonymous session cookie, and
// proxies to Spring POST /api/v1/tts/playback. voice_id is interpreted server-side so repeated
// playback of the same text and voice can use the shared backend TTS cache.

const DEFAULT_TTS_VOICE_ID =
  process.env.PHRASELOG_DEFAULT_TTS_VOICE_ID ?? "shimmer";

type TtsRequestBody = {
  text?: unknown;
  voice_id?: unknown;
  expression_variant_id?: unknown;
};

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

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
    return jsonError(400, "validation_failed", "요청을 확인해 주세요.");
  }

  const text = body.text;
  if (typeof text !== "string" || text.trim().length === 0) {
    return jsonError(400, "validation_failed", "재생할 문장이 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  const cookieStore = await cookies();
  const sessionToken = cookieStore.get(ANON_SESSION_COOKIE)?.value;

  if (!userId && !sessionToken) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const voiceId =
    typeof body.voice_id === "string" && body.voice_id.length > 0
      ? body.voice_id
      : DEFAULT_TTS_VOICE_ID;
  const expressionVariantId =
    typeof body.expression_variant_id === "string" &&
    body.expression_variant_id.length > 0
      ? body.expression_variant_id
      : undefined;

  try {
    const result = await requestTtsPlayback({
      text,
      voiceId,
      ...(expressionVariantId ? { expressionVariantId } : {}),
      ...(userId ? { userId } : { sessionToken }),
    });
    return Response.json(result, { status: 200 });
  } catch {
    // Do not log raw text or tokens on backend/network errors (SECURITY.md).
    return jsonError(
      502,
      "tts_failed",
      "음성 재생에 실패했어요. 다시 시도해 주세요.",
    );
  }
}

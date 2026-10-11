import { headers } from "next/headers";

import { auth } from "@/auth";
import {
  SubmitTurnError,
  submitAudioTurn,
  submitTurn,
} from "@/lib/practice/submit-turn";

// S12 롤플레이 턴 제출 BFF. (#61 · 백엔드 #60)
//
// 사용자가 텍스트(JSON) 또는 음성(multipart `audio`)으로 답하면 호출한다. 상태 변경이므로 Origin이 있으면 호스트와 일치해야 한다.
// NextAuth 세션을 서버에서 확인하고 user_id로 내부 인증 토큰을 발급해
// POST /api/v1/practice/sessions/{id}/turns { text_content } 를 프록시한다. 세션 미발견은 404.
// 원문(text_content)과 녹음은 사용자 데이터이므로 로깅하지 않는다(SECURITY.md).
// 음성은 전사를 서버가 하므로 BFF는 Blob을 그대로 전달한다. 실패는 상태별로 구분해 돌려준다 —
// 빈 전사(422)와 녹음 규격 오류(400)는 사용자가 해야 할 행동이 다르다.

type TurnRequestBody = {
  text_content?: unknown;
};

/** 60초 WebM/Opus는 1MB를 넘지 않는다. /api/transcriptions와 같은 상한을 쓴다. */
const MAX_AUDIO_BYTES = 4 * 1024 * 1024;

function isMultipart(request: Request): boolean {
  return (
    request.headers.get("content-type")?.toLowerCase().startsWith("multipart/form-data") ??
    false
  );
}

/** 백엔드 오류를 사용자가 취할 행동이 갈리는 단위로 옮긴다. */
function turnFailure(error: unknown): Response {
  if (error instanceof SubmitTurnError) {
    if (error.isNotFound) {
      return jsonError(404, "not_found", "세션을 찾지 못했어요.");
    }
    if (error.isConflict) {
      // 세션이 완료/포기 상태 — 재시도 대상이 아니다.
      return jsonError(409, "session_not_active", "이미 끝난 세션이에요.");
    }
    if (error.isEmptyTranscript) {
      return jsonError(422, "empty_transcript", "잘 안 들렸어요. 다시 말해주세요.");
    }
    if (error.isPayloadTooLarge) {
      return jsonError(413, "audio_too_large", "녹음이 너무 길어요. 다시 녹음해주세요.");
    }
    if (error.isInvalidAudio) {
      return jsonError(400, "validation_failed", "녹음이 올바르지 않아요. 다시 녹음해주세요.");
    }
    if (error.isTimeout) {
      return jsonError(504, "timeout", "응답이 늦어지고 있어요. 다시 시도해주세요.");
    }
    if (error.isRateLimited) {
      return jsonError(429, "rate_limited", "요청이 많아요. 잠시 후 다시 시도해주세요.");
    }
  }
  return jsonError(502, "turn_failed", "메시지를 보내지 못했어요. 다시 시도해주세요.");
}

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
  context: { params: Promise<{ session_id: string }> },
): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  const { session_id: sessionId } = await context.params;
  if (!sessionId) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  // 클라이언트가 준 Idempotency-Key를 그대로 백엔드로 전달한다 — 재시도 시 같은 키여야
  // 중복 턴 생성을 막는다(s12.md edge case). 없으면 lib이 1회용 키를 만든다.
  const idempotencyKey =
    (await headers()).get("idempotency-key") ?? undefined;

  if (isMultipart(request)) {
    let audio: unknown;
    try {
      audio = (await request.formData()).get("audio");
    } catch {
      return jsonError(400, "validation_failed", "녹음을 확인해주세요.");
    }
    if (!(audio instanceof Blob) || audio.size === 0) {
      return jsonError(400, "validation_failed", "녹음을 확인해주세요.");
    }
    if (audio.size > MAX_AUDIO_BYTES) {
      return jsonError(413, "audio_too_large", "녹음이 너무 길어요. 다시 녹음해주세요.");
    }

    try {
      const result = await submitAudioTurn(
        { userId, sessionId, audio },
        { idempotencyKey },
      );
      return Response.json(result, { status: 200 });
    } catch (error) {
      return turnFailure(error);
    }
  }

  let body: TurnRequestBody;
  try {
    body = (await request.json()) as TurnRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  if (typeof body.text_content !== "string" || body.text_content.trim().length === 0) {
    return jsonError(400, "validation_failed", "메시지를 입력해주세요.");
  }

  try {
    const result = await submitTurn(
      { userId, sessionId, textContent: body.text_content },
      { idempotencyKey },
    );
    return Response.json(result, { status: 200 });
  } catch (error) {
    return turnFailure(error);
  }
}

import "server-only";

import {
  type InternalAuthSubject,
  mintInternalAuthToken,
} from "../internal-auth";

// Server-side helper for the card playback BFF (#40).
//
// It mints the internal auth token from either an authenticated user_id or anonymous
// session_token, then proxies text, voice_id, and optional expression_variant_id to Spring's
// POST /api/v1/tts/playback endpoint. The backend owns cache lookup, synthesis, S3 storage,
// presigned URL generation, and cache_status.

export type FetchLike = (request: Request) => Promise<Response>;

export type TtsPlaybackInput = {
  text: string;
  voiceId: string;
  expressionVariantId?: string;
  /** 인증 사용자. sessionToken과 정확히 하나만 준다(XOR). */
  userId?: string;
  /** 가입 전 익명 세션. userId와 정확히 하나만 준다(XOR). */
  sessionToken?: string;
};

export type TtsPlaybackOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type TtsPlaybackResult = {
  audio_url: string;
  duration_ms?: number;
  cache_status?: "hit" | "miss";
};

/** TTS 재생을 받지 못했을 때 라우트는 502로 정규화하고 클라이언트는 실패 토스트를 띄운다. */
export class TtsPlaybackError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`TTS playback failed with status ${status}`);
    this.name = "TtsPlaybackError";
    this.status = status;
  }
}

export async function requestTtsPlayback(
  input: TtsPlaybackInput,
  options: TtsPlaybackOptions = {},
): Promise<TtsPlaybackResult> {
  const backendBaseUrl =
    options.backendBaseUrl ?? process.env.PHRASELOG_BACKEND_BASE_URL;
  const internalAuthSecret =
    options.internalAuthSecret ?? process.env.INTERNAL_AUTH_SECRET;
  const fetcher: FetchLike = options.fetcher ?? ((request) => fetch(request));

  if (!backendBaseUrl) {
    throw new Error("PHRASELOG_BACKEND_BASE_URL is required");
  }
  if (!internalAuthSecret) {
    throw new Error("INTERNAL_AUTH_SECRET is required");
  }

  const internalAuthToken = await mintInternalAuthToken(
    subjectFor(input),
    internalAuthSecret,
  );

  const requestBody: Record<string, string> = {
    text: input.text,
    voice_id: input.voiceId,
  };
  if (input.expressionVariantId) {
    requestBody.expression_variant_id = input.expressionVariantId;
  }

  const response = await fetcher(
    new Request(
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/tts/playback`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-auth": internalAuthToken,
        },
        body: JSON.stringify(requestBody),
      },
    ),
  );

  if (!response.ok) {
    throw new TtsPlaybackError(response.status);
  }

  return (await response.json()) as TtsPlaybackResult;
}

/** userId XOR sessionToken 중 정확히 하나여야 내부 인증 클레임 불명확성을 막는다. */
function subjectFor(input: TtsPlaybackInput): InternalAuthSubject {
  if (input.userId && input.sessionToken) {
    throw new Error("requestTtsPlayback: pass exactly one of userId or sessionToken");
  }
  if (input.userId) {
    return { userId: input.userId };
  }
  if (input.sessionToken) {
    return { sessionToken: input.sessionToken };
  }
  throw new Error("requestTtsPlayback: one of userId or sessionToken is required");
}

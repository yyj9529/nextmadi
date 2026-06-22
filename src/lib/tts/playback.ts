import "server-only";

import {
  type InternalAuthSubject,
  mintInternalAuthToken,
} from "../internal-auth";

// BFF → Spring Boot TTS 재생. (#40)
//
// 카드 재생 버튼이 부르는 POST /api/tts/playback의 서버 측 구현. 인증 사용자는 user_id,
// 가입 전 익명은 session_token으로 내부 인증 토큰을 발급하고, Spring의 POST /api/v1/tts/playback
// 으로 { text, voice_id }를 프록시한다. 백엔드는 tts_audio_cache를 조회해 miss면 OpenAI TTS +
// S3 기록 후 서명 URL을, hit이면 기존 URL을 돌려준다(cache_status로 구분).
//
// 주의: TTS 백엔드(#30)는 아직 미구현이다. 그 동안 이 호출은 404/오류로 떨어지고, 라우트가
// 정규화한 오류를 받아 카드가 "음성 재생 실패" 토스트를 보여준다(S07 US2-AC3 폴백). #30이
// 머지되면 동일 계약으로 즉시 동작한다.

export type FetchLike = (request: Request) => Promise<Response>;

export type TtsPlaybackInput = {
  text: string;
  voiceId: string;
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

/** TTS 재생을 받지 못했을 때. 라우트는 502로 정규화하고 클라이언트는 실패 토스트를 띄운다. */
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

  const response = await fetcher(
    new Request(
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/tts/playback`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-auth": internalAuthToken,
        },
        body: JSON.stringify({ text: input.text, voice_id: input.voiceId }),
      },
    ),
  );

  if (!response.ok) {
    throw new TtsPlaybackError(response.status);
  }

  return (await response.json()) as TtsPlaybackResult;
}

/** userId XOR sessionToken — 둘 중 정확히 하나여야 내부 인증 클레임 불변식을 만족한다. */
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

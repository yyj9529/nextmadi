import "server-only";

import {
  type InternalAuthSubject,
  mintInternalAuthToken,
} from "../internal-auth";

// BFF → Spring Boot STT 호출. (#36, #29, ADR-010)
//
// 음성 입력 2-스텝 흐름의 1단계다: 녹음 오디오를 여기서 전사만 하고, 사용자가 전사문을 확인·
// 편집한 뒤에야 POST /analysis(텍스트)로 넘어간다. 오전사가 LLM 분석 호출이나 익명 2회 한도를
// 태우지 않게 하려는 것이 이 분리의 이유다(s02.md US1-4, 2026-06-10 결정).
//
// 인증 사용자는 user_id, 가입 전 S02 호출은 session_token으로 내부 인증 토큰을 발급한다 —
// analysis 경로와 정확히 같은 XOR 불변식(internal-auth.ts)이다.
//
// 주의: 오디오와 전사문은 사용자 데이터다 — 로그에 남기지 않는다(SECURITY.md).

export type FetchLike = (request: Request) => Promise<Response>;

export type TranscribeInput = {
  audio: Blob;
  /** 인증 사용자. sessionToken과 정확히 하나만 준다(XOR). */
  userId?: string;
  /** 가입 전 익명 세션. userId와 정확히 하나만 준다(XOR). */
  sessionToken?: string;
  /** 익명 호출의 IP/day 한도 집계용. 인증 호출에는 싣지 않는다. */
  clientIp?: string | null;
};

export type TranscribeOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  signal?: AbortSignal;
};

export type TranscribeResult = {
  transcript: string;
  /** 모델에 따라 없을 수 있다(AI_PIPELINE.md Stage 1). UI는 쓰지 않고 백엔드 로깅용이다. */
  sttConfidence: number | null;
};

type ApiErrorBody = {
  error_code?: string;
  user_message?: string;
  retryable?: boolean;
};

/** 백엔드가 이 파일명을 Whisper로 그대로 넘긴다 — 확장자가 있어야 포맷을 인식한다. */
const AUDIO_FILENAME = "recording.webm";

export class TranscribeError extends Error {
  readonly status: number;
  readonly errorCode: string;

  constructor(status: number, body: ApiErrorBody) {
    super(body.error_code ?? "Transcription failed");
    this.name = "TranscribeError";
    this.status = status;
    this.errorCode = body.error_code ?? "transcription_failed";
  }

  /** 오디오 자체가 규격 밖 — 같은 파일을 다시 보내도 결과가 같다. 재녹음이 필요하다. */
  get isInvalidAudio(): boolean {
    return this.status === 400;
  }

  /** 전사 결과가 비었다 — 다시 말해달라고 안내한다. */
  get isEmptyTranscript(): boolean {
    // 상태코드가 아니라 계약 토큰으로 판별한다. 백엔드는 규격 오류(400 validation_failed)와
    // 무발화(422 empty_transcript)를 다른 코드로 내리고, 아래 200+공백 방어도 같은 코드를 쓴다.
    return this.errorCode === "empty_transcript";
  }

  /**
   * 익명 하루 STT 한도를 다 썼다. 상태코드로 보면 안 된다 — 공급자 혼잡(provider_429)도 429라
   * 같은 상태코드로는 "잠시 후 재시도"와 "오늘은 안 됨"이 뒤섞인다.
   */
  get isRateLimited(): boolean {
    return this.errorCode === "rate_limit_exceeded";
  }

  /** 공급자 혼잡·타임아웃·네트워크 — 같은 녹음을 재전송할 가치가 있다. */
  get isRetryable(): boolean {
    return !this.isInvalidAudio && !this.isEmptyTranscript && !this.isRateLimited;
  }
}

/** userId XOR sessionToken — 둘 중 정확히 하나여야 내부 인증 클레임 불변식을 만족한다. */
function subjectFor(input: TranscribeInput): InternalAuthSubject {
  if (input.userId && input.sessionToken) {
    throw new Error("transcribeAudio: pass exactly one of userId or sessionToken");
  }
  if (input.userId) {
    return { userId: input.userId };
  }
  if (input.sessionToken) {
    return { sessionToken: input.sessionToken };
  }
  throw new Error("transcribeAudio: one of userId or sessionToken is required");
}

export async function transcribeAudio(
  input: TranscribeInput,
  options: TranscribeOptions = {},
): Promise<TranscribeResult> {
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

  const subject = subjectFor(input);
  const internalAuthToken = await mintInternalAuthToken(
    subject,
    internalAuthSecret,
  );

  const form = new FormData();
  form.append("audio", input.audio, AUDIO_FILENAME);

  // content-type은 지정하지 않는다 — FormData가 multipart boundary를 직접 붙여야 한다.
  const headers: Record<string, string> = {
    "x-internal-auth": internalAuthToken,
  };
  // 익명(session_token) 호출만 IP/day 집계 대상이다. analysis 경로와 같은 규칙이다.
  if ("sessionToken" in subject && input.clientIp) {
    headers["x-client-ip"] = input.clientIp;
  }

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/transcriptions`, {
      method: "POST",
      headers,
      body: form,
      signal: options.signal,
    }),
  );

  if (!response.ok) {
    throw new TranscribeError(response.status, await readError(response));
  }

  const body = (await response.json()) as {
    transcript?: unknown;
    stt_confidence?: unknown;
  };
  const transcript =
    typeof body.transcript === "string" ? body.transcript.trim() : "";
  if (transcript.length === 0) {
    // 백엔드도 빈 전사를 막지만, 200 + 공백이 오는 경우를 여기서 한 번 더 잡는다.
    // 422는 우리 내부 분류값이다 — 백엔드가 쓰는 코드가 아니다.
    throw new TranscribeError(422, { error_code: "empty_transcript" });
  }

  return {
    transcript,
    sttConfidence:
      typeof body.stt_confidence === "number" ? body.stt_confidence : null,
  };
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { error_code: "transcription_failed" };
  }
}

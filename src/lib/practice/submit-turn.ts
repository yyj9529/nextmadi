import "server-only";

import { randomUUID } from "node:crypto";

import { mintInternalAuthToken } from "../internal-auth";
import type { SubmitTurnResult } from "./types";

// BFF → Spring Boot 롤플레이 턴 제출. (#61, S12 · 백엔드 #60)
//
// 사용자가 텍스트로 답하면 이 함수가 POST /api/v1/practice/sessions/{id}/turns { text_content }를
// 프록시한다. 서버는 코치 응답(Sonnet) + 턴 피드백(Haiku)을 만들어 user_turn/coach_turn/feedback과
// session_status(active|completed)를 돌려준다. 빈/저신뢰 입력은 turn_consumed=false + retry_prompt로
// 턴을 소비하지 않는다(s12.md US2 AC5).
//
// 음성 턴(submitAudioTurn)은 녹음 Blob을 multipart `audio` 파트로 그대로 전달한다 — 전사(Whisper)와
// 신뢰도 판정은 서버가 한 요청에서 처리한다(s12.md US2). 빈 전사는 turn_consumed=false가 아니라
// 422 empty_transcript로 오므로 SubmitTurnError가 상태와 error_code를 보존한다.
// Idempotency-Key로 재전송 시 서버가 중복 턴 생성을 막는다(s12.md edge case, 네트워크 끊김).

export type FetchLike = (request: Request) => Promise<Response>;

export type SubmitTurnInput = {
  userId: string;
  sessionId: string;
  textContent: string;
};

export type SubmitAudioTurnInput = {
  userId: string;
  sessionId: string;
  /** 브라우저가 녹음한 WebM/Opus. 전사는 서버가 한다. */
  audio: Blob;
};

export type SubmitTurnOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
  /** 한 요청에서 STT + 코치 + 피드백 + TTS를 처리하므로 여유 있게 둔다(AI_PIPELINE.md 합계 약 70초). */
  timeoutMs?: number;
};

/** 음성 턴 최악 합계(STT 30 + 코치 15 + 피드백 10 + TTS 15)에 여유를 더한 값. */
export const SUBMIT_TURN_TIMEOUT_MS = 90_000;

export class SubmitTurnError extends Error {
  readonly status: number;
  /** 백엔드 오류 본문의 error_code. 본문이 JSON이 아니면 null. */
  readonly errorCode: string | null;

  constructor(status: number, errorCode: string | null = null) {
    super(`Submit turn failed (${status})`);
    this.name = "SubmitTurnError";
    this.status = status;
    this.errorCode = errorCode;
  }

  /** 세션 미발견/미소유 — S12는 안내 후 복귀한다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** 세션이 더 이상 active가 아님(완료/포기) — 재시도가 아니라 결과/홈으로 보내야 한다. */
  get isConflict(): boolean {
    return this.status === 409;
  }

  /** 말소리가 잡히지 않음(무발화). 같은 녹음 재전송은 소용없고 다시 말해야 한다. */
  get isEmptyTranscript(): boolean {
    return this.status === 422;
  }

  /** 녹음 규격 오류(WebM/Opus 아님, 60초 초과 등). 재전송해도 같다. */
  get isInvalidAudio(): boolean {
    return this.status === 400;
  }

  /** 업로드가 서버 한도를 넘음. 같은 녹음을 다시 보내도 같은 결과다. */
  get isPayloadTooLarge(): boolean {
    return this.status === 413;
  }

  /** STT 또는 LLM 시간 초과 — 재시도 가능. */
  get isTimeout(): boolean {
    return this.status === 408 || this.status === 504;
  }

  /** 외부 제공자 429 — 잠시 후 재시도. */
  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

export async function submitTurn(
  input: SubmitTurnInput,
  options: SubmitTurnOptions = {},
): Promise<SubmitTurnResult> {
  return postTurn(
    input,
    { kind: "json", body: JSON.stringify({ text_content: input.textContent }) },
    options,
  );
}

export async function submitAudioTurn(
  input: SubmitAudioTurnInput,
  options: SubmitTurnOptions = {},
): Promise<SubmitTurnResult> {
  const form = new FormData();
  // 파일명은 서버가 확장자 기본값(audio.webm)으로 대체하므로 의미 없지만, multipart에서 파일 파트가
  // 되려면 필요하다.
  form.append("audio", input.audio, "recording.webm");
  return postTurn(input, { kind: "form", body: form }, options);
}

type TurnPayload =
  | { kind: "json"; body: string }
  // content-type을 직접 쓰지 않는다 — fetch가 boundary를 붙여 만든다.
  | { kind: "form"; body: FormData };

async function postTurn(
  input: { userId: string; sessionId: string },
  payload: TurnPayload,
  options: SubmitTurnOptions,
): Promise<SubmitTurnResult> {
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
    { userId: input.userId },
    internalAuthSecret,
  );

  const url = `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/practice/sessions/${encodeURIComponent(
    input.sessionId,
  )}/turns`;

  const headers: Record<string, string> = {
    "x-internal-auth": internalAuthToken,
    "idempotency-key": options.idempotencyKey ?? randomUUID(),
  };
  if (payload.kind === "json") {
    headers["content-type"] = "application/json";
  }

  const response = await fetcher(
    new Request(url, {
      method: "POST",
      headers,
      body: payload.body,
      signal: AbortSignal.timeout(options.timeoutMs ?? SUBMIT_TURN_TIMEOUT_MS),
    }),
  );

  if (!response.ok) {
    throw new SubmitTurnError(response.status, await readErrorCode(response));
  }

  const body = (await response.json()) as Partial<SubmitTurnResult>;

  return {
    turn_consumed: body.turn_consumed ?? false,
    retry_prompt: body.retry_prompt ?? null,
    user_turn: body.user_turn ?? null,
    coach_turn: body.coach_turn ?? null,
    feedback: body.feedback ?? null,
    session_status: body.session_status ?? "active",
  };
}

async function readErrorCode(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { error_code?: unknown };
    return typeof body.error_code === "string" ? body.error_code : null;
  } catch {
    return null;
  }
}

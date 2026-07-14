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
// 음성(multipart) 경로는 이번 티켓 범위 밖 — 텍스트 경로만 배선한다.
// Idempotency-Key로 재전송 시 서버가 중복 턴 생성을 막는다(s12.md edge case, 네트워크 끊김).

export type FetchLike = (request: Request) => Promise<Response>;

export type SubmitTurnInput = {
  userId: string;
  sessionId: string;
  textContent: string;
};

export type SubmitTurnOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
};

export class SubmitTurnError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Submit turn failed (${status})`);
    this.name = "SubmitTurnError";
    this.status = status;
  }

  /** 세션 미발견/미소유 — S12는 안내 후 복귀한다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** 세션이 더 이상 active가 아님(완료/포기) — 재시도가 아니라 결과/홈으로 보내야 한다. */
  get isConflict(): boolean {
    return this.status === 409;
  }
}

export async function submitTurn(
  input: SubmitTurnInput,
  options: SubmitTurnOptions = {},
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

  const response = await fetcher(
    new Request(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-internal-auth": internalAuthToken,
        "idempotency-key": options.idempotencyKey ?? randomUUID(),
      },
      body: JSON.stringify({ text_content: input.textContent }),
    }),
  );

  if (!response.ok) {
    throw new SubmitTurnError(response.status);
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

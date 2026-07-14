import "server-only";

import { mintInternalAuthToken } from "../internal-auth";
import type { RoleplaySession, RoleplayTurn } from "./types";

// BFF → Spring Boot 롤플레이 세션 상태 조회. (#61, S12 · 백엔드 #59)
//
// S12 화면 진입/새로고침/복귀 시 GET /api/v1/practice/sessions/{session_id}를 프록시한다.
// 서버는 같은 user_id로 소유권을 확인하고, 없거나 소유하지 않거나 잘못된 id는 구분 불가능한
// 404로 응답한다(s12.md edge case). turns는 turn_number 오름차순으로 대화 이력 복원에 쓴다.

export type FetchLike = (request: Request) => Promise<Response>;

export type GetSessionInput = {
  userId: string;
  sessionId: string;
};

export type GetSessionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

/** 세션을 찾지 못했거나 호출자 소유가 아닐 때(404). S12는 이후 홈/라이브러리로 복귀한다. */
export class SessionNotFoundError extends Error {
  constructor() {
    super("Practice session not found");
    this.name = "SessionNotFoundError";
  }
}

export async function getSession(
  input: GetSessionInput,
  options: GetSessionOptions = {},
): Promise<RoleplaySession> {
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
  )}`;

  const response = await fetcher(
    new Request(url, {
      method: "GET",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (response.status === 404) {
    throw new SessionNotFoundError();
  }
  if (!response.ok) {
    throw new Error(`GET /practice/sessions failed with status ${response.status}`);
  }

  const body = (await response.json()) as RoleplaySession & {
    turns?: RoleplayTurn[] | null;
  };

  return {
    id: body.id,
    status: body.status,
    planned_turns: body.planned_turns,
    coach_id: body.coach_id,
    expression_id: body.expression_id ?? null,
    started_at: body.started_at,
    ended_at: body.ended_at ?? null,
    turns: body.turns ?? [],
  };
}

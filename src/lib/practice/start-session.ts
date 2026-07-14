import "server-only";

import { randomUUID } from "node:crypto";

import { mintInternalAuthToken } from "../internal-auth";
import type { RoleplayTurn, StartSessionResult } from "./types";

// BFF → Spring Boot 롤플레이 세션 시작. (#61, S12 · 백엔드 #59)
//
// S09 "이 표현으로 연습하기"가 이 함수를 부른다. 인증 사용자의 user_id로 내부 인증 토큰
// (X-Internal-Auth)을 발급하고 POST /api/v1/practice/sessions { expression_id, coach_id? }를
// 프록시한다. 서버는 세션 초기화(roleplay_session_init)로 planned_turns와 오프닝 코치 턴을
// 만들어 201로 돌려준다. 일일 2회 한도 초과는 429, 미소유/삭제 표현은 404.
//
// Idempotency-Key는 중복 제출(연타/재시도) 시 같은 세션을 재사용하기 위한 것으로,
// 호출당 한 번 생성해 넘긴다. coach_id를 안 주면 서버가 users.selected_coach_id를 쓴다.

export type FetchLike = (request: Request) => Promise<Response>;

export type StartSessionInput = {
  userId: string;
  expressionId: string;
  /** 생략 시 서버가 사용자의 선택 코치를 쓴다. 코치 전환(S12 US3)에서만 지정. */
  coachId?: string;
};

export type StartSessionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
};

export class StartSessionError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Start session failed (${status})`);
    this.name = "StartSessionError";
    this.status = status;
  }

  /** 일일 롤플레이 한도(2회) 초과 — S12 US1 AC3의 차단 모달로 매핑. */
  get isRateLimited(): boolean {
    return this.status === 429;
  }

  /** 표현 미발견/미소유/삭제 — S09로 복귀 안내. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function startSession(
  input: StartSessionInput,
  options: StartSessionOptions = {},
): Promise<StartSessionResult> {
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

  const body: Record<string, string> = { expression_id: input.expressionId };
  if (input.coachId) {
    body.coach_id = input.coachId;
  }

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/practice/sessions`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-internal-auth": internalAuthToken,
        "idempotency-key": options.idempotencyKey ?? randomUUID(),
      },
      body: JSON.stringify(body),
    }),
  );

  if (response.status !== 201) {
    throw new StartSessionError(response.status);
  }

  const parsed = (await response.json()) as {
    id: string;
    status: StartSessionResult["status"];
    planned_turns: number;
    coach_id: string;
    opening_turn?: RoleplayTurn | null;
  };

  return {
    id: parsed.id,
    status: parsed.status,
    planned_turns: parsed.planned_turns,
    coach_id: parsed.coach_id,
    opening_turn: parsed.opening_turn ?? null,
  };
}

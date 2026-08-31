import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 오늘의 사용량 조회. (#56, S11 설정)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// GET /api/v1/usage/today를 호출한다. analysis_limit은 null이 "무제한"이라는 뜻이며
// (openapi 계약), 0과 구분해야 하므로 ?? 폴백을 쓰지 않는다.

export type FetchLike = (request: Request) => Promise<Response>;

export type GetUsageTodayInput = {
  userId: string;
};

export type GetUsageTodayOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type UsageTodayResult = {
  roleplay_session_count: number;
  daily_roleplay_limit: number;
  analysis_count: number;
  /** null = 무제한 (v1). 0과 다르다. */
  analysis_limit: number | null;
};

export class GetUsageTodayError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Get usage today failed (${status})`);
    this.name = "GetUsageTodayError";
    this.status = status;
  }
}

export async function getUsageToday(
  input: GetUsageTodayInput,
  options: GetUsageTodayOptions = {},
): Promise<UsageTodayResult> {
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

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/usage/today`, {
      method: "GET",
      headers: {
        "x-internal-auth": internalAuthToken,
      },
    }),
  );

  if (!response.ok) {
    throw new GetUsageTodayError(response.status);
  }

  const body = (await response.json()) as Partial<UsageTodayResult>;

  return {
    roleplay_session_count: body.roleplay_session_count ?? 0,
    daily_roleplay_limit: body.daily_roleplay_limit ?? 2,
    analysis_count: body.analysis_count ?? 0,
    analysis_limit: body.analysis_limit ?? null,
  };
}

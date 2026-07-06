import "server-only";

import { mintInternalAuthToken } from "../internal-auth";
import type { Coach } from "../mock-api";

// BFF → Spring Boot 코치 목록 조회. (#53, S03b)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고 GET /api/v1/coaches를
// 호출한다. 응답은 { coaches: Coach[] } 봉투이며 그대로 카드 렌더에 쓴다. 코치가 시드되지
// 않았으면(빈 목록) 호출 측이 온보딩을 막는 에러로 처리한다(s03b.md edge case).

export type FetchLike = (request: Request) => Promise<Response>;

export type ListCoachesInput = {
  userId: string;
};

export type ListCoachesOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

type CoachListResponse = {
  coaches?: Coach[];
};

export class ListCoachesError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`List coaches failed (${status})`);
    this.name = "ListCoachesError";
    this.status = status;
  }
}

export async function listCoaches(
  input: ListCoachesInput,
  options: ListCoachesOptions = {},
): Promise<Coach[]> {
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
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/coaches`, {
      method: "GET",
      headers: {
        "x-internal-auth": internalAuthToken,
      },
    }),
  );

  if (!response.ok) {
    throw new ListCoachesError(response.status);
  }

  const body = (await response.json()) as CoachListResponse;
  return body.coaches ?? [];
}

import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 현재 사용자 조회. (#56, S11 설정)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고 GET /api/v1/me를
// 호출한다. 상태 변경이 없는 read이며, 설정 화면 첫 페인트의 프로필 섹션 데이터다.
// 사용자는 항상 토큰 principal로 식별한다(쿼리/본문 아님).

export type FetchLike = (request: Request) => Promise<Response>;

export type GetMeInput = {
  userId: string;
};

export type GetMeOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type GetMeResult = {
  id: string;
  email: string;
  display_name: string | null;
  selected_coach_id: string | null;
  is_onboarded: boolean;
  created_at: string;
  scheduled_deletion_at: string | null;
};

export class GetMeError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Get me failed (${status})`);
    this.name = "GetMeError";
    this.status = status;
  }
}

export async function getMe(
  input: GetMeInput,
  options: GetMeOptions = {},
): Promise<GetMeResult> {
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
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/me`, {
      method: "GET",
      headers: {
        "x-internal-auth": internalAuthToken,
      },
    }),
  );

  if (!response.ok) {
    throw new GetMeError(response.status);
  }

  const body = (await response.json()) as Partial<GetMeResult>;

  return {
    id: body.id as string,
    email: body.email as string,
    display_name: body.display_name ?? null,
    selected_coach_id: body.selected_coach_id ?? null,
    is_onboarded: body.is_onboarded ?? false,
    created_at: body.created_at as string,
    scheduled_deletion_at: body.scheduled_deletion_at ?? null,
  };
}

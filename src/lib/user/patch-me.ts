import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 현재 사용자 부분 수정 호출. (#53, S03b / S11)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고 PATCH /api/v1/me를
// 호출한다. 부분 수정: 전달한 필드만 바뀐다(선택 코치, 온보딩 완료, 닉네임). 호출 측이 보낸
// 값만 본문에 담고, 사용자는 항상 토큰 principal로 식별한다(본문 아님).

export type FetchLike = (request: Request) => Promise<Response>;

export type PatchMeInput = {
  userId: string;
  selectedCoachId?: string;
  isOnboarded?: boolean;
  displayName?: string;
};

export type PatchMeOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type PatchMeResult = {
  id: string;
  email: string;
  display_name: string | null;
  selected_coach_id: string | null;
  is_onboarded: boolean;
  created_at: string;
  scheduled_deletion_at: string | null;
};

export class PatchMeError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Patch me failed (${status})`);
    this.name = "PatchMeError";
    this.status = status;
  }
}

export async function patchMe(
  input: PatchMeInput,
  options: PatchMeOptions = {},
): Promise<PatchMeResult> {
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

  // 부분 수정: 전달된 필드만 본문에 담는다(누락 = 변경 안 함).
  const body: Record<string, unknown> = {};
  if (input.selectedCoachId !== undefined) {
    body.selected_coach_id = input.selectedCoachId;
  }
  if (input.isOnboarded !== undefined) {
    body.is_onboarded = input.isOnboarded;
  }
  if (input.displayName !== undefined) {
    body.display_name = input.displayName;
  }

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/me`, {
      method: "PATCH",
      headers: {
        "content-type": "application/json",
        "x-internal-auth": internalAuthToken,
      },
      body: JSON.stringify(body),
    }),
  );

  if (!response.ok) {
    throw new PatchMeError(response.status);
  }

  return (await response.json()) as PatchMeResult;
}

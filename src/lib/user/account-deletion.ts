import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 계정 삭제 예약/취소. (#24, S11 User Story 3)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고 DELETE /api/v1/me 와
// POST /api/v1/me/cancel-deletion 을 호출한다. 둘 다 204라서 돌려줄 본문이 없다 —
// 성공은 "던지지 않음"으로만 표현된다.
//
// 여기서 지우는 데이터는 없다. 백엔드는 users.scheduled_deletion_at 만 세우고, 실제 삭제는
// 유예 기간이 지난 뒤 E02.3 잡이 한다. 그래서 유예 중 재로그인이 복원으로 성립한다.

export type FetchLike = (request: Request) => Promise<Response>;

export type AccountDeletionInput = {
  userId: string;
};

export type AccountDeletionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class AccountDeletionError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Account deletion request failed (${status})`);
    this.name = "AccountDeletionError";
    this.status = status;
  }
}

/** DELETE /me — 14일 유예 시작. */
export async function scheduleAccountDeletion(
  input: AccountDeletionInput,
  options: AccountDeletionOptions = {},
): Promise<void> {
  await call("/api/v1/me", "DELETE", input, options);
}

/** POST /me/cancel-deletion — 예약 취소. 예약이 없었으면 no-op. */
export async function cancelAccountDeletion(
  input: AccountDeletionInput,
  options: AccountDeletionOptions = {},
): Promise<void> {
  await call("/api/v1/me/cancel-deletion", "POST", input, options);
}

async function call(
  path: string,
  method: "DELETE" | "POST",
  input: AccountDeletionInput,
  options: AccountDeletionOptions,
): Promise<void> {
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
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}${path}`, {
      method,
      headers: {
        "x-internal-auth": internalAuthToken,
      },
    }),
  );

  if (!response.ok) {
    throw new AccountDeletionError(response.status);
  }
}

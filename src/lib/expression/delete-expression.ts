import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 표현 soft-delete. (#47, S09 표현 상세)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// DELETE /api/v1/expressions/{expression_id} 를 호출한다. 백엔드는 deleted_at을 세팅하고
// 204를 돌려준다. 없거나 소유하지 않거나 이미 삭제된 표현은 404(isNotFound) — 다른 탭에서
// 이미 삭제한 경합도 여기에 해당한다(s09.md edge case).

export type FetchLike = (request: Request) => Promise<Response>;

export type DeleteExpressionInput = {
  userId: string;
  expressionId: string;
};

export type DeleteExpressionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class DeleteExpressionError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Delete expression failed (${status})`);
    this.name = "DeleteExpressionError";
    this.status = status;
  }

  /** 없음/미소유/이미 삭제 — UI는 이미 사라진 것으로 보고 /library로 복귀한다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function deleteExpression(
  input: DeleteExpressionInput,
  options: DeleteExpressionOptions = {},
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

  const url = `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/expressions/${encodeURIComponent(
    input.expressionId,
  )}`;

  const response = await fetcher(
    new Request(url, {
      method: "DELETE",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (!response.ok) {
    throw new DeleteExpressionError(response.status);
  }
}

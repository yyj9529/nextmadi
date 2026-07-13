import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 복습 큐에서 제거. (#47, S09 표현 상세)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// POST /api/v1/review/{review_card_id}/remove-from-queue 를 호출한다. 백엔드는
// removed_from_queue_at을 세팅하고 204를 돌려준다 — 표현은 라이브러리에 남고 /review에서만
// 빠진다. 카드가 없거나 소유하지 않으면 404(isNotFound). re-add 방향은 상세 GET이 제거된
// 카드의 id를 내려주지 않아 v1에서 보류한다(s09.md).

export type FetchLike = (request: Request) => Promise<Response>;

export type RemoveFromQueueInput = {
  userId: string;
  reviewCardId: string;
};

export type RemoveFromQueueOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class RemoveFromQueueError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Remove from queue failed (${status})`);
    this.name = "RemoveFromQueueError";
    this.status = status;
  }

  /** 카드 없음/미소유(다른 탭에서 이미 제거 등) — UI는 제거된 것으로 보고 상태를 갱신한다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function removeFromQueue(
  input: RemoveFromQueueInput,
  options: RemoveFromQueueOptions = {},
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

  const url = `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/review/${encodeURIComponent(
    input.reviewCardId,
  )}/remove-from-queue`;

  const response = await fetcher(
    new Request(url, {
      method: "POST",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (!response.ok) {
    throw new RemoveFromQueueError(response.status);
  }
}

import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 복습 평가 제출 호출. (#50, S10 복습)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// POST /api/v1/review/{review_card_id}/submit { rating } 를 호출한다. 서버는 review_attempts
// 한 행을 기록하고 review_cards를 갱신한 뒤 산정된 다음 간격(next_interval_days)을 돌려준다.
// 카드를 찾지 못하면(404) 호출 측이 그대로 404로 매핑한다.

export type FetchLike = (request: Request) => Promise<Response>;

export type ReviewRating = "hard" | "good" | "easy";

export type SubmitRatingInput = {
  userId: string;
  reviewCardId: string;
  rating: ReviewRating;
};

export type SubmitRatingOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type SubmitRatingResult = {
  review_card_id: string;
  previous_interval_days: number;
  next_interval_days: number;
  next_review_at: string;
};

export class SubmitRatingError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Submit rating failed (${status})`);
    this.name = "SubmitRatingError";
    this.status = status;
  }

  /** 카드를 찾지 못함(미소유/삭제) — S10은 카드를 비전진하고 재시도/이탈을 안내한다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function submitRating(
  input: SubmitRatingInput,
  options: SubmitRatingOptions = {},
): Promise<SubmitRatingResult> {
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
    new Request(
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/review/${encodeURIComponent(
        input.reviewCardId,
      )}/submit`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-auth": internalAuthToken,
        },
        body: JSON.stringify({ rating: input.rating }),
      },
    ),
  );

  if (!response.ok) {
    throw new SubmitRatingError(response.status);
  }

  return (await response.json()) as SubmitRatingResult;
}

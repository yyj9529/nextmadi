import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 오늘의 복습 큐 조회 호출. (#50, S10 복습)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// GET /api/v1/review/today?limit&exclude_ids 를 호출한다. 읽기 전용이라 상태 변경이 없다.
// exclude_ids는 이번 세션에서 이미 로드한 review_card_id의 CSV로, 다음 배치 중복을 막는다.

export type FetchLike = (request: Request) => Promise<Response>;

export type ReviewVariantDto = {
  id: string;
  variant_order: number;
  tone_label: string | null;
  english_text: string;
  ipa: string | null;
  korean_pronunciation: string | null;
  pronunciation_tip: string | null;
  cultural_tip: string | null;
};

export type ReviewCardDto = {
  id: string;
  expression_id: string;
  next_review_at: string;
  last_reviewed_at: string | null;
  last_rating: string | null;
  current_interval_days: number;
  original_situation: string;
  variant: ReviewVariantDto;
};

export type ReviewTodayResult = {
  cards: ReviewCardDto[];
  total_due: number;
};

export type GetReviewTodayInput = {
  userId: string;
  /** 페이지 크기. 기본/최대는 백엔드가 클램프(기본 10, max 50). */
  limit?: number | null;
  /** 이번 세션에서 이미 로드한 review_card_id 목록. CSV로 직렬화해 전달. */
  excludeIds?: string[] | null;
};

export type GetReviewTodayOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class GetReviewTodayError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Get review today failed (${status})`);
    this.name = "GetReviewTodayError";
    this.status = status;
  }
}

export async function getReviewToday(
  input: GetReviewTodayInput,
  options: GetReviewTodayOptions = {},
): Promise<ReviewTodayResult> {
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

  const url = new URL(
    `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/review/today`,
  );
  if (input.limit !== undefined && input.limit !== null) {
    url.searchParams.set("limit", String(input.limit));
  }
  if (input.excludeIds && input.excludeIds.length > 0) {
    url.searchParams.set("exclude_ids", input.excludeIds.join(","));
  }

  const response = await fetcher(
    new Request(url, {
      method: "GET",
      headers: {
        "x-internal-auth": internalAuthToken,
      },
    }),
  );

  if (!response.ok) {
    throw new GetReviewTodayError(response.status);
  }

  const body = (await response.json()) as {
    cards?: ReviewCardDto[];
    total_due?: number;
  };

  return {
    cards: body.cards ?? [],
    total_due: body.total_due ?? 0,
  };
}

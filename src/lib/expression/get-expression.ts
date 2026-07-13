import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 표현 상세 조회. (#47, S09 표현 상세)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// GET /api/v1/expressions/{expression_id} 를 호출한다. 백엔드는 같은 user_id로 소유권을
// 확인하고, 없거나 소유하지 않거나 soft-delete된 표현은 구분 불가능한 404로 응답한다(s09.md
// edge case). review_card_id는 removed_from_queue_at IS NULL일 때만 값이 오므로,
// null이면 "복습 큐에서 제거됨(또는 큐 없음)" 상태다.

export type FetchLike = (request: Request) => Promise<Response>;

export type ExpressionVariant = {
  id: string;
  variant_order: number;
  tone_label: string | null;
  english_text: string;
  ipa: string | null;
  korean_pronunciation: string | null;
  pronunciation_tip: string | null;
  cultural_tip: string | null;
  tts_audio_url: string | null;
};

export type ExpressionDetail = {
  id: string;
  source_type: string;
  original_situation: string;
  selected_variant_id: string;
  variants: ExpressionVariant[];
  /** 큐에 있을 때만 값이 온다(removed면 null). 있으면 remove-from-queue 대상 id. */
  review_card_id: string | null;
  next_review_at: string | null;
  created_at: string;
};

export type GetExpressionInput = {
  userId: string;
  expressionId: string;
};

export type GetExpressionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

/** 표현을 찾지 못했거나 호출자 소유가 아닐 때(404). S09는 404 상태 후 /library로 복귀한다. */
export class ExpressionNotFoundError extends Error {
  constructor() {
    super("Expression not found");
    this.name = "ExpressionNotFoundError";
  }
}

export async function getExpression(
  input: GetExpressionInput,
  options: GetExpressionOptions = {},
): Promise<ExpressionDetail> {
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
      method: "GET",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (response.status === 404) {
    throw new ExpressionNotFoundError();
  }
  if (!response.ok) {
    throw new Error(`GET /expressions failed with status ${response.status}`);
  }

  const body = (await response.json()) as ExpressionDetail & {
    variants?: ExpressionVariant[] | null;
  };

  return {
    id: body.id,
    source_type: body.source_type,
    original_situation: body.original_situation,
    selected_variant_id: body.selected_variant_id,
    variants: body.variants ?? [],
    review_card_id: body.review_card_id ?? null,
    next_review_at: body.next_review_at ?? null,
    created_at: body.created_at,
  };
}

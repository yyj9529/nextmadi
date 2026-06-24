import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 표현 목록 조회 호출. (#46, S08 라이브러리)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// GET /api/v1/expressions?q&cursor&limit 을 호출한다. 읽기 전용이므로 상태 변경이 없고,
// cursor는 백엔드가 발급한 불투명 토큰을 그대로 전달한다. 검색어(q)는 사용자 입력이므로
// 로그에 남기지 않는다.

export type FetchLike = (request: Request) => Promise<Response>;

export type ListExpressionsInput = {
  userId: string;
  /** 검색 키워드(한국어/영어). 백엔드가 100자로 클램프한다. */
  q?: string | null;
  /** 이전 응답의 next_cursor. 없으면 첫 페이지. */
  cursor?: string | null;
  /** 페이지 크기. 기본/최대는 백엔드가 클램프(기본 20, max 100). */
  limit?: number | null;
};

export type ListExpressionsOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type ExpressionListItem = {
  id: string;
  original_situation: string;
  english_text: string;
  tone_label: string | null;
  created_at: string;
};

export type ListExpressionsResult = {
  items: ExpressionListItem[];
  next_cursor: string | null;
};

export class ListExpressionsError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`List expressions failed (${status})`);
    this.name = "ListExpressionsError";
    this.status = status;
  }
}

export async function listExpressions(
  input: ListExpressionsInput,
  options: ListExpressionsOptions = {},
): Promise<ListExpressionsResult> {
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
    `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/expressions`,
  );
  if (input.q) {
    url.searchParams.set("q", input.q);
  }
  if (input.cursor) {
    url.searchParams.set("cursor", input.cursor);
  }
  if (input.limit !== undefined && input.limit !== null) {
    url.searchParams.set("limit", String(input.limit));
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
    throw new ListExpressionsError(response.status);
  }

  const body = (await response.json()) as {
    items?: ExpressionListItem[];
    next_cursor?: string | null;
  };

  return {
    items: body.items ?? [],
    next_cursor: body.next_cursor ?? null,
  };
}

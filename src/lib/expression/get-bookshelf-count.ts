import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 북카운트 조회. (#46, S08 헤더 "📚 N권")
//
// GET /api/v1/expressions 응답에는 전체 개수가 없으므로, 홈 대시보드 집계 API(#95)의
// bookshelf_count(활성·비삭제 표현 수)를 재사용한다. 헤더 숫자 하나를 위한 read이며,
// 실패는 호출 측에서 graceful하게(숫자 숨김) 처리한다.

export type FetchLike = (request: Request) => Promise<Response>;

export type GetBookshelfCountInput = {
  userId: string;
};

export type GetBookshelfCountOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class BookshelfCountError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Bookshelf count failed (${status})`);
    this.name = "BookshelfCountError";
    this.status = status;
  }
}

export async function getBookshelfCount(
  input: GetBookshelfCountInput,
  options: GetBookshelfCountOptions = {},
): Promise<number> {
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
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/home/dashboard`,
      {
        method: "GET",
        headers: {
          "x-internal-auth": internalAuthToken,
        },
      },
    ),
  );

  if (!response.ok) {
    throw new BookshelfCountError(response.status);
  }

  const body = (await response.json()) as { bookshelf_count?: number };
  return body.bookshelf_count ?? 0;
}

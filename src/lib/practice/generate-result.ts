import "server-only";

import { mintInternalAuthToken } from "../internal-auth";
import type { RoleplayResult } from "./types";

// BFF → Spring Boot 롤플레이 결과 생성/조회. (#63, S12b · 백엔드 #62)
//
// S12b 진입 시 세션의 result_json이 null이면 이 함수를 부른다. 서버는 Sonnet 결과 프롬프트를
// 1회 호출해 practice_sessions.result_json에 저장하고, 재호출 시 캐시된 결과를 재과금 없이
// 돌려준다(s12b.md AC1). 세션이 completed가 아니면 409, 미소유/미발견은 404.

export type FetchLike = (request: Request) => Promise<Response>;

export type GenerateResultInput = {
  userId: string;
  sessionId: string;
};

export type GenerateResultOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class GenerateResultError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Generate result failed (${status})`);
    this.name = "GenerateResultError";
    this.status = status;
  }

  /** 세션 미발견/미소유(404) — UI는 홈으로 복귀 안내. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** 세션이 completed 상태가 아님(409) — UI는 활성 세션 /practice/{id}로 복귀(s12b.md AC3). */
  get isNotCompleted(): boolean {
    return this.status === 409;
  }
}

export async function generateResult(
  input: GenerateResultInput,
  options: GenerateResultOptions = {},
): Promise<RoleplayResult> {
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

  const url = `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/practice/sessions/${encodeURIComponent(
    input.sessionId,
  )}/result`;

  const response = await fetcher(
    new Request(url, {
      method: "POST",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (response.status !== 200) {
    throw new GenerateResultError(response.status);
  }

  return (await response.json()) as RoleplayResult;
}

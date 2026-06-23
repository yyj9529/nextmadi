import "server-only";

import { randomUUID } from "node:crypto";

import {
  type InternalAuthSubject,
  mintInternalAuthToken,
} from "../internal-auth";

// BFF → Spring Boot 분석 생성 호출. (#35, ADR-010)
//
// S02 체험 화면이 "분석 요청"을 누르면 BFF 라우트가 이 함수를 부른다. 인증 사용자는 user_id,
// 가입 전(S02) 익명 호출은 session_token으로 내부 인증 토큰(X-Internal-Auth)을 발급해 Spring의
// POST /api/v1/analysis로 { input_text }를 프록시한다 — 정확히 하나만 담는 XOR 불변식
// (internal-auth.ts)을 그대로 따른다. 익명 호출은 anonymous_analysis_usage(IP/day) 집계를 위해
// X-Client-IP를 함께 보낸다(openapi ClientIp). 백엔드는 201로 analysis_requests 행을 만들고
// 3개 variant를 담은 AnalysisRequest를 돌려준다. 한도 초과 시 429.
//
// 주의: session_token과 원문(input_text)은 자격증명/사용자 데이터다 — 로그에 남기지 않는다
// (SECURITY.md).

export type FetchLike = (request: Request) => Promise<Response>;

export type SubmitAnalysisInput = {
  inputText: string;
  /** S01 예시 카드로 진입했을 때의 선택 참조. */
  landingExampleId?: string;
  /** 인증 사용자. sessionToken과 정확히 하나만 준다(XOR). */
  userId?: string;
  /** 가입 전 익명 세션. userId와 정확히 하나만 준다(XOR). */
  sessionToken?: string;
  /** 익명 호출의 IP/day rate-limit 집계용. 익명일 때만 의미가 있다. */
  clientIp?: string | null;
};

export type SubmitAnalysisOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
};

export type SubmitAnalysisResult = {
  /** openapi AnalysisRequest.id — 곧 analysis_request_id다. S07 라우팅에 쓴다. */
  analysisRequestId: string;
};

type ApiErrorBody = {
  error_code?: string;
  user_message?: string;
  developer_hint?: string;
  retryable?: boolean;
};

export class SubmitAnalysisError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly userMessage?: string;
  readonly retryable?: boolean;

  constructor(status: number, body: ApiErrorBody) {
    super(body.user_message ?? body.error_code ?? "Analysis failed");
    this.name = "SubmitAnalysisError";
    this.status = status;
    this.errorCode = body.error_code ?? "analysis_failed";
    this.userMessage = body.user_message;
    this.retryable = body.retryable;
  }

  /** 익명 일일 한도 초과(429) — 라우트는 rate_limit_exceeded로 정규화한다(S02 US2). */
  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

export async function submitAnalysis(
  input: SubmitAnalysisInput,
  options: SubmitAnalysisOptions = {},
): Promise<SubmitAnalysisResult> {
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

  const subject = subjectFor(input);
  const internalAuthToken = await mintInternalAuthToken(
    subject,
    internalAuthSecret,
  );

  const body: Record<string, unknown> = { input_text: input.inputText };
  if (input.landingExampleId) {
    body.landing_example_id = input.landingExampleId;
  }

  const headers: Record<string, string> = {
    "content-type": "application/json",
    "x-internal-auth": internalAuthToken,
    "idempotency-key": options.idempotencyKey ?? randomUUID(),
  };
  // 익명(session_token) 호출만 IP/day 집계 대상이다. 인증 호출엔 IP를 싣지 않는다.
  if ("sessionToken" in subject && input.clientIp) {
    headers["x-client-ip"] = input.clientIp;
  }

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/analysis`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    }),
  );

  if (response.status === 201) {
    const created = (await response.json()) as { id?: string };
    if (!created.id) {
      throw new SubmitAnalysisError(502, { error_code: "analysis_failed" });
    }
    return { analysisRequestId: created.id };
  }

  throw new SubmitAnalysisError(response.status, await readError(response));
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { error_code: "analysis_failed" };
  }
}

/** userId XOR sessionToken — 둘 중 정확히 하나여야 내부 인증 클레임 불변식을 만족한다. */
function subjectFor(input: SubmitAnalysisInput): InternalAuthSubject {
  if (input.userId && input.sessionToken) {
    throw new Error("submitAnalysis: pass exactly one of userId or sessionToken");
  }
  if (input.userId) {
    return { userId: input.userId };
  }
  if (input.sessionToken) {
    return { sessionToken: input.sessionToken };
  }
  throw new Error("submitAnalysis: one of userId or sessionToken is required");
}

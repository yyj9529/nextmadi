import "server-only";

import { randomUUID } from "node:crypto";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 표현 저장 호출. (#42, ADR-010)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고 POST /api/v1/expressions를
// 호출한다. 가입 전 분석을 claim해야 하면 원래 익명 session_token을 "요청 본문"으로 함께 보낸다 —
// 내부 JWS는 여전히 user_id만 담으므로(XOR 불변식 유지) auth 동작은 바뀌지 않는다.
// session_token은 익명 세션의 자격증명이므로 로그에 남기지 않는다.

export type FetchLike = (request: Request) => Promise<Response>;

export type SaveExpressionInput = {
  userId: string;
  analysisRequestId: string;
  selectedVariantOrder?: number;
  /** 가입 전 분석을 claim할 때만 존재하는 원래 익명 session_token. */
  claimSessionToken?: string | null;
};

export type SaveExpressionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
};

export type SavedExpression = {
  id: string;
  analysis_request_id: string;
  selected_variant_id: string | null;
  // 그 외 Expression 필드는 호출 측에서 필요할 때 좁혀 쓴다.
  [key: string]: unknown;
};

export type SaveExpressionResult = {
  expression: SavedExpression;
  /** 동일 분석을 다시 저장(409): UI는 이미 저장됨으로 취급한다(S07 US3-AC5). */
  alreadySaved: boolean;
};

type ApiErrorBody = {
  error_code?: string;
  user_message?: string;
  developer_hint?: string;
  retryable?: boolean;
};

export class SaveExpressionError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly userMessage?: string;
  readonly retryable?: boolean;

  constructor(status: number, body: ApiErrorBody) {
    super(body.user_message ?? body.error_code ?? "Save failed");
    this.name = "SaveExpressionError";
    this.status = status;
    this.errorCode = body.error_code ?? "save_failed";
    this.userMessage = body.user_message;
    this.retryable = body.retryable;
  }

  /** 가입 전 claim 실패(토큰 불일치/만료) — S07은 사과 문구 + /home CTA를 보여준다. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function saveExpression(
  input: SaveExpressionInput,
  options: SaveExpressionOptions = {},
): Promise<SaveExpressionResult> {
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

  const body: Record<string, unknown> = {
    analysis_request_id: input.analysisRequestId,
  };
  if (input.selectedVariantOrder !== undefined) {
    body.selected_variant_order = input.selectedVariantOrder;
  }
  if (input.claimSessionToken) {
    body.session_token = input.claimSessionToken;
  }

  const response = await fetcher(
    new Request(`${backendBaseUrl.replace(/\/+$/, "")}/api/v1/expressions`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-internal-auth": internalAuthToken,
        "idempotency-key": options.idempotencyKey ?? randomUUID(),
      },
      body: JSON.stringify(body),
    }),
  );

  if (response.status === 201 || response.status === 409) {
    const expression = (await response.json()) as SavedExpression;
    return { expression, alreadySaved: response.status === 409 };
  }

  throw new SaveExpressionError(response.status, await readError(response));
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { error_code: "save_failed" };
  }
}

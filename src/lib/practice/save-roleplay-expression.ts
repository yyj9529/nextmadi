import "server-only";

import { randomUUID } from "node:crypto";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 롤플레이 추천 표현 저장. (#63, S12b · 백엔드 #62)
//
// S12b 추천 표현 카드의 "저장"이 이 함수를 부른다. 서버는 result_json.recommended_expressions의
// 해당 인덱스로 expressions(1) + expression_variants(3) + review_cards(1)를 만들어 라이브러리에
// 넣는다(s12b.md US2 AC1). Idempotency-Key로 연타/재시도가 중복 행을 만들지 않게 한다(AC4):
// 같은 키의 중복 저장은 서버가 409 + 기존 expression을 돌려주고, UI는 성공으로 취급한다.
//
// 409는 두 가지다 — (a) 중복 저장(기존 expression 본문) → 성공, (b) 세션에 캐시 결과가 없거나
// completed가 아님(ErrorResponse 본문) → 실패. 응답 본문에 표현 id가 있으면 (a)로 구분한다.

export type FetchLike = (request: Request) => Promise<Response>;

export type SaveRoleplayExpressionInput = {
  userId: string;
  sessionId: string;
  recommendedExpressionIndex: number;
};

export type SaveRoleplayExpressionOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  idempotencyKey?: string;
};

export type SavedRoleplayExpression = {
  id: string;
  /** 같은 Idempotency-Key/중복 저장으로 서버가 기존 행을 돌려준 경우(AC4). */
  alreadySaved: boolean;
};

export class SaveRoleplayExpressionError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Save roleplay expression failed (${status})`);
    this.name = "SaveRoleplayExpressionError";
    this.status = status;
  }

  /** 세션 미발견/미소유(404). */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function saveRoleplayExpression(
  input: SaveRoleplayExpressionInput,
  options: SaveRoleplayExpressionOptions = {},
): Promise<SavedRoleplayExpression> {
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
  )}/save-expression`;

  const response = await fetcher(
    new Request(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-internal-auth": internalAuthToken,
        "idempotency-key": options.idempotencyKey ?? randomUUID(),
      },
      body: JSON.stringify({
        recommended_expression_index: input.recommendedExpressionIndex,
      }),
    }),
  );

  if (response.status === 201 || response.status === 409) {
    const body = (await readJson(response)) as { id?: unknown };
    if (typeof body.id === "string") {
      return { id: body.id, alreadySaved: response.status === 409 };
    }
    // 409인데 표현 본문이 아님 → 캐시 결과 없음/미완료 등 실패로 취급.
    throw new SaveRoleplayExpressionError(409);
  }

  throw new SaveRoleplayExpressionError(response.status);
}

async function readJson(response: Response): Promise<Record<string, unknown>> {
  try {
    return (await response.json()) as Record<string, unknown>;
  } catch {
    return {};
  }
}

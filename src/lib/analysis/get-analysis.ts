import "server-only";

import {
  type InternalAuthSubject,
  mintInternalAuthToken,
} from "../internal-auth";

// BFF → Spring Boot 분석 조회. (#40)
//
// S07 페이지 진입 시 GET /api/v1/analysis/{id}를 호출한다. 인증 사용자는 user_id,
// 가입 전(S02) 익명 접근은 session_token으로 내부 인증 토큰(X-Internal-Auth)을 발급한다 —
// 정확히 하나만 담는 XOR 불변식(internal-auth.ts)을 그대로 따른다. 백엔드는 같은 클레임으로
// 소유권을 확인하고, 소유하지 않거나 없는 id는 구분 불가능한 404로 응답한다(S07 US1-AC3).

export type FetchLike = (request: Request) => Promise<Response>;

export type AnalysisVariant = {
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

export type AnalysisResult = {
  id: string;
  input_text: string;
  variants: AnalysisVariant[];
  prompt_version?: string;
  created_at: string;
  /** Absent for existing v1 results. */
  result_type?: "expressions" | "needs_context" | "word";
  assessment?: { verdict: "suggestion" | "appropriate" | "needs_adjustment"; summary: string; reason: string } | null;
  question?: string | null;
  word?: { english: string; meaning_ko: string } | null;
};

export type GetAnalysisInput = {
  analysisRequestId: string;
  /** 인증 사용자. sessionToken과 정확히 하나만 준다(XOR). */
  userId?: string;
  /** 가입 전 익명 세션. userId와 정확히 하나만 준다(XOR). */
  sessionToken?: string;
};

export type GetAnalysisOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

/** 분석을 찾지 못했거나 호출자 소유가 아닐 때(404). S07은 not-found 상태 + CTA를 보여준다. */
export class AnalysisNotFoundError extends Error {
  constructor() {
    super("Analysis not found");
    this.name = "AnalysisNotFoundError";
  }
}

export async function getAnalysis(
  input: GetAnalysisInput,
  options: GetAnalysisOptions = {},
): Promise<AnalysisResult> {
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
    subjectFor(input),
    internalAuthSecret,
  );

  const url = `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/analysis/${encodeURIComponent(
    input.analysisRequestId,
  )}`;

  const response = await fetcher(
    new Request(url, {
      method: "GET",
      headers: { "x-internal-auth": internalAuthToken },
    }),
  );

  if (response.status === 404) {
    throw new AnalysisNotFoundError();
  }
  if (!response.ok) {
    throw new Error(`GET /analysis failed with status ${response.status}`);
  }

  return (await response.json()) as AnalysisResult;
}

/** userId XOR sessionToken — 둘 중 정확히 하나여야 내부 인증 클레임 불변식을 만족한다. */
function subjectFor(input: GetAnalysisInput): InternalAuthSubject {
  if (input.userId && input.sessionToken) {
    throw new Error("getAnalysis: pass exactly one of userId or sessionToken");
  }
  if (input.userId) {
    return { userId: input.userId };
  }
  if (input.sessionToken) {
    return { sessionToken: input.sessionToken };
  }
  throw new Error("getAnalysis: one of userId or sessionToken is required");
}

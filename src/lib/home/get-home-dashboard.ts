import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// BFF → Spring Boot 홈 대시보드 집계 조회. (#54, S04 홈)
//
// 인증 사용자의 user_id로 내부 인증 토큰(X-Internal-Auth)을 발급하고
// GET /api/v1/home/dashboard 를 호출한다. 첫 페인트용 단일 집계 read이며 상태 변경이 없다.
// 응답: user + coach(nullable) + bookshelf_count + recent_expressions(최대 2) +
// due_review_count + today_roleplay_session_count + daily_roleplay_limit.

export type FetchLike = (request: Request) => Promise<Response>;

export type DashboardUserDto = {
  id: string;
  email: string;
  display_name: string | null;
  selected_coach_id: string | null;
  is_onboarded: boolean;
  created_at: string;
};

export type DashboardCoachDto = {
  id: string;
  slug: "mia" | "david" | "sarah";
  display_name: string;
  persona_summary: string;
  tts_voice_id: string;
};

export type DashboardExpressionDto = {
  id: string;
  original_situation: string;
  english_text: string;
  tone_label: string | null;
  created_at: string;
};

export type HomeDashboardResult = {
  user: DashboardUserDto;
  coach: DashboardCoachDto | null;
  bookshelf_count: number;
  recent_expressions: DashboardExpressionDto[];
  due_review_count: number;
  today_roleplay_session_count: number;
  daily_roleplay_limit: number;
};

export type GetHomeDashboardInput = {
  userId: string;
};

export type GetHomeDashboardOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export class GetHomeDashboardError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Get home dashboard failed (${status})`);
    this.name = "GetHomeDashboardError";
    this.status = status;
  }
}

export async function getHomeDashboard(
  input: GetHomeDashboardInput,
  options: GetHomeDashboardOptions = {},
): Promise<HomeDashboardResult> {
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
    throw new GetHomeDashboardError(response.status);
  }

  const body = (await response.json()) as Partial<HomeDashboardResult>;

  return {
    user: body.user as DashboardUserDto,
    coach: body.coach ?? null,
    bookshelf_count: body.bookshelf_count ?? 0,
    recent_expressions: body.recent_expressions ?? [],
    due_review_count: body.due_review_count ?? 0,
    today_roleplay_session_count: body.today_roleplay_session_count ?? 0,
    daily_roleplay_limit: body.daily_roleplay_limit ?? 2,
  };
}

import "server-only";

// BFF(SSR) → Spring Boot 공개 조회. (#33, S01)
//
// GET /api/v1/landing/examples 는 openapi security:[] 공개 엔드포인트다 — 내부 인증
// 토큰이 필요 없다(InternalAuthFilter 화이트리스트). 활성 풀에서 무작위 3개를 돌려주며,
// "매 방문마다 다른 무작위 표본"(s01) 계약을 지키려고 no-store로 캐시를 끈다.
//
// 스펙 s01 UI states: 네트워크 오류 또는 0건이면 예시 섹션을 조용히 생략한다. 방문자는
// 이 데이터 없이도 CTA로 행동할 수 있으므로, 실패·비정상 응답을 예외로 올리지 않고 빈
// 배열로 정규화한다. 백엔드 미구현(#32) 동안에도 페이지는 CTA-only로 정상 렌더된다.

export type LandingExample = {
  id: string;
  korean_text: string;
};

export type FetchLike = (request: Request) => Promise<Response>;

export type GetLandingExamplesOptions = {
  backendBaseUrl?: string;
  fetcher?: FetchLike;
};

const MAX_EXAMPLES = 3;

export async function getLandingExamples(
  options: GetLandingExamplesOptions = {},
): Promise<LandingExample[]> {
  const backendBaseUrl =
    options.backendBaseUrl ?? process.env.PHRASELOG_BACKEND_BASE_URL;
  const fetcher: FetchLike = options.fetcher ?? ((request) => fetch(request));

  if (!backendBaseUrl) {
    return [];
  }

  try {
    const response = await fetcher(
      new Request(
        `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/landing/examples`,
        {
          method: "GET",
          headers: { accept: "application/json" },
          cache: "no-store",
        },
      ),
    );

    if (!response.ok) {
      return [];
    }

    const body = (await response.json()) as { examples?: unknown };
    return normalizeExamples(body.examples);
  } catch {
    // 네트워크/파싱 오류 — 예시 섹션은 조용히 생략된다(s01). 토큰이 없으므로 로깅 위험 없음.
    return [];
  }
}

function normalizeExamples(value: unknown): LandingExample[] {
  if (!Array.isArray(value)) {
    return [];
  }

  const examples: LandingExample[] = [];
  for (const item of value) {
    if (!item || typeof item !== "object") {
      continue;
    }
    const { id, korean_text: koreanText } = item as Record<string, unknown>;
    if (typeof id === "string" && typeof koreanText === "string") {
      examples.push({ id, korean_text: koreanText });
    }
    if (examples.length === MAX_EXAMPLES) {
      break;
    }
  }
  return examples;
}

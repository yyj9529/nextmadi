import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

// --- 제어 가능한 테스트 더블 ---------------------------------------------------

type Session = { user?: { id?: string } } | null;
let currentSession: Session = null;

const cookieJar = new Map<string, string>();
const cookieSets: Array<{ name: string; value: string; options: unknown }> = [];

let headerMap = new Map<string, string>();

// submitAnalysis 더블: 호출 인자를 기록하고, 미리 정한 결과를 돌려주거나 던진다.
let submitImpl: (input: unknown) => Promise<{ analysisRequestId: string }>;
const submitCalls: unknown[] = [];

class FakeSubmitAnalysisError extends Error {
  readonly status: number;
  readonly errorCode: string;
  constructor(status: number, errorCode = "analysis_failed") {
    super(errorCode);
    this.name = "SubmitAnalysisError";
    this.status = status;
    this.errorCode = errorCode;
  }
  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

mock.module("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) =>
      cookieJar.has(name) ? { value: cookieJar.get(name) } : undefined,
    set: (name: string, value: string, options: unknown) => {
      cookieSets.push({ name, value, options });
      cookieJar.set(name, value);
    },
  }),
  headers: async () => ({
    get: (name: string) => headerMap.get(name.toLowerCase()) ?? null,
  }),
}));

mock.module("@/auth", () => ({
  auth: async () => currentSession,
}));

mock.module("@/lib/analysis/submit-analysis", () => ({
  submitAnalysis: async (input: unknown) => {
    submitCalls.push(input);
    return submitImpl(input);
  },
  SubmitAnalysisError: FakeSubmitAnalysisError,
}));

const { POST } = await import("./route");

function jsonRequest(body: unknown): Request {
  return new Request("http://localhost/api/analysis", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  currentSession = null;
  cookieJar.clear();
  cookieSets.length = 0;
  headerMap = new Map();
  submitCalls.length = 0;
  submitImpl = async () => ({ analysisRequestId: "analysis-1" });
});

describe("POST /api/analysis", () => {
  test("anonymous first visit mints an httpOnly session cookie and returns 201", async () => {
    headerMap.set("x-forwarded-for", "203.0.113.7, 10.0.0.1");

    const response = await POST(jsonRequest({ input_text: "친구한테 서운해요" }));

    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({ analysis_request_id: "analysis-1" });

    // 새 익명 토큰을 httpOnly 쿠키로 심는다.
    expect(cookieSets).toHaveLength(1);
    expect(cookieSets[0].name).toBe("phraselog_anon_session");
    expect((cookieSets[0].options as { httpOnly?: boolean }).httpOnly).toBe(true);

    // submitAnalysis는 session_token + 첫 IP만 받는다(forwarding chain 아님).
    const call = submitCalls[0] as {
      sessionToken?: string;
      userId?: string;
      clientIp?: string;
    };
    expect(call.sessionToken).toBe(cookieSets[0].value);
    expect(call.userId).toBeUndefined();
    expect(call.clientIp).toBe("203.0.113.7");
  });

  test("reuses an existing anonymous cookie without minting a new one", async () => {
    cookieJar.set("phraselog_anon_session", "existing-token");

    const response = await POST(jsonRequest({ input_text: "안녕" }));

    expect(response.status).toBe(201);
    expect(cookieSets).toHaveLength(0);
    expect((submitCalls[0] as { sessionToken?: string }).sessionToken).toBe(
      "existing-token",
    );
  });

  test("authenticated calls use user_id and never a session_token", async () => {
    currentSession = { user: { id: "user-9" } };

    await POST(jsonRequest({ input_text: "안녕" }));

    const call = submitCalls[0] as { userId?: string; sessionToken?: string };
    expect(call.userId).toBe("user-9");
    expect(call.sessionToken).toBeUndefined();
    expect(cookieSets).toHaveLength(0);
  });

  test("normalizes a 429 from the backend to rate_limit_exceeded", async () => {
    submitImpl = async () => {
      throw new FakeSubmitAnalysisError(429, "rate_limit_exceeded");
    };

    const response = await POST(jsonRequest({ input_text: "안녕" }));

    expect(response.status).toBe(429);
    expect(await response.json()).toMatchObject({
      error_code: "rate_limit_exceeded",
    });
  });

  test("rejects empty and over-long input with 400", async () => {
    const empty = await POST(jsonRequest({ input_text: "" }));
    expect(empty.status).toBe(400);

    const tooLong = await POST(jsonRequest({ input_text: "a".repeat(501) }));
    expect(tooLong.status).toBe(400);

    // submitAnalysis까지 가지 않는다.
    expect(submitCalls).toHaveLength(0);
  });
});

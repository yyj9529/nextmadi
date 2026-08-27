import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

// --- 제어 가능한 테스트 더블 ---------------------------------------------------

type Session = { user?: { id?: string } } | null;
let currentSession: Session = null;

const cookieJar = new Map<string, string>();
const cookieSets: Array<{ name: string; value: string; options: unknown }> = [];

let headerMap = new Map<string, string>();

let transcribeImpl: (input: unknown) => Promise<{
  transcript: string;
  sttConfidence: number | null;
}>;
const transcribeCalls: unknown[] = [];

// 실제 TranscribeError 를 쓴다. 가짜로 status→분류 매핑을 재구현하면 실제 분류가 바뀌어도
// 이 테스트가 계속 통과한다 — 빈 전사 계약이 어긋난 채 머지된 원인이 정확히 그것이었다.
// server-only 모킹 뒤에 동적 import 해야 한다(정적 import 는 호이스팅돼 모킹보다 먼저 평가된다).
const { TranscribeError } = await import("@/lib/voice/transcribe");

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

mock.module("@/lib/voice/transcribe", () => ({
  transcribeAudio: async (input: unknown) => {
    transcribeCalls.push(input);
    return transcribeImpl(input);
  },
  TranscribeError,
}));

const { POST } = await import("./route");

function audioRequest(blob: Blob = webmBlob()): Request {
  const form = new FormData();
  form.append("audio", blob, "recording.webm");
  return new Request("http://localhost/api/transcriptions", {
    method: "POST",
    body: form,
  });
}

function webmBlob(bytes = 2048): Blob {
  return new Blob([new Uint8Array(bytes)], { type: "audio/webm" });
}

beforeEach(() => {
  currentSession = null;
  cookieJar.clear();
  cookieSets.length = 0;
  headerMap = new Map();
  transcribeCalls.length = 0;
  transcribeImpl = async () => ({
    transcript: "줄 새치기한 사람한테 한마디 하고 싶었어요",
    sttConfidence: 0.9,
  });
});

describe("POST /api/transcriptions", () => {
  test("익명 첫 호출은 session_token 쿠키를 심고 전사문을 돌려준다", async () => {
    const response = await POST(audioRequest());

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      transcript: "줄 새치기한 사람한테 한마디 하고 싶었어요",
      stt_confidence: 0.9,
    });

    expect(cookieSets).toHaveLength(1);
    expect(cookieSets[0].name).toBe("phraselog_anon_session");
    expect((cookieSets[0].options as { httpOnly?: boolean }).httpOnly).toBe(true);

    const call = transcribeCalls[0] as {
      sessionToken?: string;
      userId?: string;
    };
    expect(call.sessionToken).toBe(cookieSets[0].value);
    expect(call.userId).toBeUndefined();
  });

  test("기존 익명 쿠키가 있으면 재사용한다 — 분석 요청과 같은 토큰이어야 한다", async () => {
    cookieJar.set("phraselog_anon_session", "existing-token");

    await POST(audioRequest());

    expect(cookieSets).toHaveLength(0);
    expect((transcribeCalls[0] as { sessionToken?: string }).sessionToken).toBe(
      "existing-token",
    );
  });

  test("로그인 사용자는 user_id로만 호출한다", async () => {
    currentSession = { user: { id: "user-9" } };

    await POST(audioRequest());

    const call = transcribeCalls[0] as { userId?: string; sessionToken?: string };
    expect(call.userId).toBe("user-9");
    expect(call.sessionToken).toBeUndefined();
    expect(cookieSets).toHaveLength(0);
  });

  test("Origin이 호스트와 다르면 403", async () => {
    headerMap.set("origin", "https://evil.test");
    headerMap.set("host", "localhost");

    const response = await POST(audioRequest());

    expect(response.status).toBe(403);
    expect(transcribeCalls).toHaveLength(0);
  });

  test("audio 파트가 없거나 비면 400", async () => {
    const empty = new Request("http://localhost/api/transcriptions", {
      method: "POST",
      body: new FormData(),
    });

    expect((await POST(empty)).status).toBe(400);
    expect((await POST(audioRequest(webmBlob(0)))).status).toBe(400);
    expect(transcribeCalls).toHaveLength(0);
  });

  test("상한을 넘는 오디오는 백엔드까지 보내지 않는다", async () => {
    const response = await POST(audioRequest(webmBlob(5 * 1024 * 1024)));

    expect(response.status).toBe(413);
    expect(transcribeCalls).toHaveLength(0);
  });

  test("빈 전사는 422로 내려 재시도 안내를 띄운다", async () => {
    transcribeImpl = async () => {
      throw new TranscribeError(422, { error_code: "empty_transcript" });
    };

    const response = await POST(audioRequest());

    expect(response.status).toBe(422);
    expect((await response.json()).error_code).toBe("empty_transcript");
  });

  test("오디오 규격 오류는 400으로 내린다", async () => {
    transcribeImpl = async () => {
      throw new TranscribeError(400, { error_code: "validation_failed" });
    };

    const response = await POST(audioRequest());

    expect(response.status).toBe(400);
    expect((await response.json()).error_code).toBe("validation_failed");
  });

  test("익명 호출은 x-forwarded-for의 첫 IP를 백엔드로 넘긴다", async () => {
    headerMap.set("x-forwarded-for", "203.0.113.7, 10.0.0.1");

    await POST(audioRequest());

    expect((transcribeCalls[0] as { clientIp?: string }).clientIp).toBe(
      "203.0.113.7",
    );
  });

  test("로그인 사용자는 IP를 넘기지 않는다 — 한도 대상이 아니다", async () => {
    currentSession = { user: { id: "user-9" } };
    headerMap.set("x-forwarded-for", "203.0.113.7");

    await POST(audioRequest());

    expect((transcribeCalls[0] as { clientIp?: string }).clientIp).toBeUndefined();
  });

  test("하루 한도 초과는 429로 내리고 재시도를 권하지 않는다", async () => {
    transcribeImpl = async () => {
      throw new TranscribeError(429, { error_code: "rate_limit_exceeded" });
    };

    const response = await POST(audioRequest());

    expect(response.status).toBe(429);
    expect((await response.json()).error_code).toBe("rate_limit_exceeded");
  });

  test("공급자 오류·타임아웃은 재시도 가능한 503으로 정규화한다", async () => {
    for (const status of [408, 429, 503]) {
      transcribeImpl = async () => {
        throw new TranscribeError(status, { error_code: "provider_error" });
      };

      const response = await POST(audioRequest());

      expect(response.status).toBe(503);
      expect((await response.json()).error_code).toBe("transcription_failed");
    }
  });
});

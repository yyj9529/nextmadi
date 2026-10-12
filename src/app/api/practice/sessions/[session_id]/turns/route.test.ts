import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

// --- 제어 가능한 테스트 더블 ---------------------------------------------------

type Session = { user?: { id?: string } } | null;
let currentSession: Session = null;
let headerMap = new Map<string, string>();

// 실제 SubmitTurnError 를 쓴다. 상태→분류 매핑(isEmptyTranscript 등)을 가짜로 다시 쓰면 실제
// 분류가 바뀌어도 이 테스트가 계속 통과한다. server-only 모킹 뒤에 동적 import 해야 한다.
const { SubmitTurnError } = await import("@/lib/practice/submit-turn");

const consumedResult = {
  turn_consumed: true,
  retry_prompt: null,
  user_turn: null,
  coach_turn: null,
  feedback: null,
  session_status: "active",
};

type TurnCall = { input: Record<string, unknown>; options: Record<string, unknown> };
const textCalls: TurnCall[] = [];
const audioCalls: TurnCall[] = [];
let textImpl: () => Promise<unknown>;
let audioImpl: () => Promise<unknown>;

mock.module("next/headers", () => ({
  headers: async () => ({
    get: (name: string) => headerMap.get(name.toLowerCase()) ?? null,
  }),
}));

mock.module("@/auth", () => ({
  auth: async () => currentSession,
}));

mock.module("@/lib/practice/submit-turn", () => ({
  SubmitTurnError,
  submitTurn: async (input: Record<string, unknown>, options: Record<string, unknown>) => {
    textCalls.push({ input, options });
    return textImpl();
  },
  submitAudioTurn: async (input: Record<string, unknown>, options: Record<string, unknown>) => {
    audioCalls.push({ input, options });
    return audioImpl();
  },
}));

const { POST } = await import("./route");

const CONTEXT = { params: Promise.resolve({ session_id: "session-1" }) };

function audioRequest(bytes = 2048): Request {
  const form = new FormData();
  form.append(
    "audio",
    new Blob([new Uint8Array(bytes)], { type: "audio/webm" }),
    "recording.webm",
  );
  return new Request("http://localhost/api/practice/sessions/session-1/turns", {
    method: "POST",
    body: form,
  });
}

function textRequest(body: unknown): Request {
  return new Request("http://localhost/api/practice/sessions/session-1/turns", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  currentSession = { user: { id: "user-1" } };
  headerMap = new Map([["idempotency-key", "idem-1"]]);
  textCalls.length = 0;
  audioCalls.length = 0;
  textImpl = async () => consumedResult;
  audioImpl = async () => consumedResult;
});

describe("POST /api/practice/sessions/{id}/turns (audio)", () => {
  test("forwards the recording and the Idempotency-Key to the audio path only", async () => {
    const response = await POST(audioRequest(), CONTEXT);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(consumedResult);
    expect(textCalls).toHaveLength(0);
    expect(audioCalls).toHaveLength(1);
    expect(audioCalls[0].input.userId).toBe("user-1");
    expect(audioCalls[0].input.sessionId).toBe("session-1");
    expect((audioCalls[0].input.audio as Blob).size).toBe(2048);
    expect(audioCalls[0].options.idempotencyKey).toBe("idem-1");
  });

  test("rejects a missing or empty audio part without calling the backend", async () => {
    const noPart = new Request("http://localhost/x", {
      method: "POST",
      body: (() => {
        const form = new FormData();
        form.append("other", "value");
        return form;
      })(),
    });
    expect((await POST(noPart, CONTEXT)).status).toBe(400);
    expect((await POST(audioRequest(0), CONTEXT)).status).toBe(400);
    expect(audioCalls).toHaveLength(0);
  });

  test("rejects a recording over the size cap with 413", async () => {
    const response = await POST(audioRequest(4 * 1024 * 1024 + 1), CONTEXT);

    expect(response.status).toBe(413);
    expect(audioCalls).toHaveLength(0);
  });

  test("requires a signed-in user", async () => {
    currentSession = null;

    expect((await POST(audioRequest(), CONTEXT)).status).toBe(401);
    expect(audioCalls).toHaveLength(0);
  });

  test("refuses a cross-origin request", async () => {
    headerMap.set("origin", "https://evil.example");
    headerMap.set("host", "localhost:3000");

    expect((await POST(audioRequest(), CONTEXT)).status).toBe(403);
    expect(audioCalls).toHaveLength(0);
  });

  test("keeps empty transcript, bad audio, timeout and rate limit apart", async () => {
    const cases: Array<[number, string, number, string]> = [
      [422, "empty_transcript", 422, "empty_transcript"],
      [400, "validation_failed", 400, "validation_failed"],
      [408, "timeout", 504, "timeout"],
      [413, "payload_too_large", 413, "audio_too_large"],
      [504, "timeout", 504, "timeout"],
      [429, "provider_429", 429, "rate_limited"],
      [503, "provider_5xx", 502, "turn_failed"],
    ];

    for (const [backendStatus, backendCode, status, errorCode] of cases) {
      audioImpl = async () => {
        throw new SubmitTurnError(backendStatus, backendCode);
      };
      const response = await POST(audioRequest(), CONTEXT);
      const body = (await response.json()) as { error_code: string };
      expect(response.status).toBe(status);
      expect(body.error_code).toBe(errorCode);
    }
  });

  test("maps a missing session to 404 and an ended session to 409", async () => {
    audioImpl = async () => {
      throw new SubmitTurnError(404);
    };
    expect((await POST(audioRequest(), CONTEXT)).status).toBe(404);

    audioImpl = async () => {
      throw new SubmitTurnError(409, "conflict");
    };
    expect((await POST(audioRequest(), CONTEXT)).status).toBe(409);
  });
});

describe("POST /api/practice/sessions/{id}/turns (text, regression)", () => {
  test("still forwards text_content as JSON", async () => {
    const response = await POST(textRequest({ text_content: "Hello" }), CONTEXT);

    expect(response.status).toBe(200);
    expect(audioCalls).toHaveLength(0);
    expect(textCalls).toHaveLength(1);
    expect(textCalls[0].input.textContent).toBe("Hello");
    expect(textCalls[0].options.idempotencyKey).toBe("idem-1");
  });

  test("still rejects blank text", async () => {
    expect((await POST(textRequest({ text_content: "  " }), CONTEXT)).status).toBe(400);
    expect(textCalls).toHaveLength(0);
  });
});

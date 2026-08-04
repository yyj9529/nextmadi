import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./transcribe";

mock.module("server-only", () => ({}));

const { transcribeAudio, TranscribeError } = await import("./transcribe");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTIONS_BASE = {
  backendBaseUrl: "http://backend.test",
  internalAuthSecret: SECRET,
};

function audio(): Blob {
  return new Blob([new Uint8Array([0x1a, 0x45, 0xdf, 0xa3])], {
    type: "audio/webm",
  });
}

function transcribed(body: unknown, status = 200): Response {
  return Response.json(body, { status });
}

describe("transcribeAudio", () => {
  test("user_id 토큰으로 multipart audio를 프록시하고 전사문을 돌려준다", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return transcribed({ transcript: "줄 새치기한 사람한테 한마디 하고 싶었어요", stt_confidence: 0.82 });
    };

    const result = await transcribeAudio(
      { audio: audio(), userId: "user-1" },
      { ...OPTIONS_BASE, fetcher },
    );

    expect(result.transcript).toBe("줄 새치기한 사람한테 한마디 하고 싶었어요");
    expect(result.sttConfidence).toBe(0.82);

    const [request] = calls;
    expect(request.method).toBe("POST");
    expect(request.url).toBe("http://backend.test/api/v1/transcriptions");

    const claims = await jwtVerify(
      request.headers.get("x-internal-auth") ?? "",
      new TextEncoder().encode(SECRET),
    );
    expect(claims.payload.user_id).toBe("user-1");
    expect(claims.payload.session_token).toBeUndefined();

    const form = await request.formData();
    const part = form.get("audio");
    expect(part).toBeInstanceOf(Blob);
    expect((part as File).name).toBe("recording.webm");
  });

  test("가입 전 익명 호출은 session_token 클레임을 싣는다", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return transcribed({ transcript: "안녕하세요" });
    };

    await transcribeAudio(
      { audio: audio(), sessionToken: "anon-1" },
      { ...OPTIONS_BASE, fetcher },
    );

    const claims = await jwtVerify(
      calls[0].headers.get("x-internal-auth") ?? "",
      new TextEncoder().encode(SECRET),
    );
    expect(claims.payload.session_token).toBe("anon-1");
    expect(claims.payload.user_id).toBeUndefined();
  });

  test("stt_confidence가 없으면 null이다 — 모델에 따라 안 올 수 있다", async () => {
    const fetcher: FetchLike = async () => transcribed({ transcript: "안녕" });

    const result = await transcribeAudio(
      { audio: audio(), userId: "user-1" },
      { ...OPTIONS_BASE, fetcher },
    );

    expect(result.sttConfidence).toBeNull();
  });

  test("user_id와 session_token을 동시에 주면 거부한다(XOR 불변식)", async () => {
    const fetcher: FetchLike = async () => transcribed({ transcript: "안녕" });

    await expect(
      transcribeAudio(
        { audio: audio(), userId: "user-1", sessionToken: "anon-1" },
        { ...OPTIONS_BASE, fetcher },
      ),
    ).rejects.toThrow(/exactly one/);
  });

  test("400은 오디오 문제로 분류한다 — 재전송해도 소용없다", async () => {
    const fetcher: FetchLike = async () =>
      transcribed({ error_code: "validation_failed" }, 400);

    const error = await transcribeAudio(
      { audio: audio(), userId: "user-1" },
      { ...OPTIONS_BASE, fetcher },
    ).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(TranscribeError);
    expect((error as InstanceType<typeof TranscribeError>).isInvalidAudio).toBe(true);
    expect((error as InstanceType<typeof TranscribeError>).isRetryable).toBe(false);
  });

  test("429·408·5xx는 재시도 가능한 실패다", async () => {
    for (const status of [408, 429, 502, 503]) {
      const fetcher: FetchLike = async () =>
        transcribed({ error_code: "provider_5xx" }, status);

      const error = await transcribeAudio(
        { audio: audio(), userId: "user-1" },
        { ...OPTIONS_BASE, fetcher },
      ).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(TranscribeError);
      expect((error as InstanceType<typeof TranscribeError>).isRetryable).toBe(true);
      expect((error as InstanceType<typeof TranscribeError>).isInvalidAudio).toBe(false);
    }
  });

  test("200인데 전사문이 비면 실패로 본다", async () => {
    const fetcher: FetchLike = async () => transcribed({ transcript: "   " });

    const error = await transcribeAudio(
      { audio: audio(), userId: "user-1" },
      { ...OPTIONS_BASE, fetcher },
    ).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(TranscribeError);
    expect((error as InstanceType<typeof TranscribeError>).isEmptyTranscript).toBe(true);
  });
});

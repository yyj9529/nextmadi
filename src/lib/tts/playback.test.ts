import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./playback";

mock.module("server-only", () => ({}));

const { requestTtsPlayback, TtsPlaybackError } = await import("./playback");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okPlayback(cacheStatus: "hit" | "miss" = "miss"): Response {
  return Response.json(
    {
      audio_url: "https://s3.test/audio.mp3",
      duration_ms: 1800,
      cache_status: cacheStatus,
    },
    { status: 200 },
  );
}

describe("requestTtsPlayback", () => {
  test("mints a user_id token and POSTs text + voice_id", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okPlayback("miss");
    };

    const result = await requestTtsPlayback(
      { text: "Excuse me.", voiceId: "shimmer", userId: "user-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.cache_status).toBe("miss");
    expect(result.audio_url).toBe("https://s3.test/audio.mp3");

    const request = calls[0];
    expect(request.method).toBe("POST");
    expect(request.url).toBe("http://backend.test/api/v1/tts/playback");
    const body = await request.json();
    expect(body).toEqual({ text: "Excuse me.", voice_id: "shimmer" });

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("surfaces cache_status hit on repeat playback", async () => {
    const fetcher: FetchLike = async () => okPlayback("hit");
    const result = await requestTtsPlayback(
      { text: "Excuse me.", voiceId: "shimmer", sessionToken: "anon-xyz" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );
    expect(result.cache_status).toBe("hit");
  });

  test("passes expression_variant_id through when provided", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okPlayback("hit");
    };

    await requestTtsPlayback(
      {
        text: "Excuse me.",
        voiceId: "shimmer",
        expressionVariantId: "00000000-0000-4000-8000-000000000030",
        userId: "user-1",
      },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const body = await calls[0].json();
    expect(body).toEqual({
      text: "Excuse me.",
      voice_id: "shimmer",
      expression_variant_id: "00000000-0000-4000-8000-000000000030",
    });
  });

  test("throws TtsPlaybackError on backend failure", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await requestTtsPlayback(
        { text: "Excuse me.", voiceId: "shimmer", userId: "user-1" },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(TtsPlaybackError);
    expect((thrown as InstanceType<typeof TtsPlaybackError>).status).toBe(404);
  });

  test("requires exactly one of userId or sessionToken", async () => {
    const fetcher: FetchLike = async () => okPlayback();
    await expect(
      requestTtsPlayback(
        { text: "x", voiceId: "shimmer" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      ),
    ).rejects.toThrow();
  });
});

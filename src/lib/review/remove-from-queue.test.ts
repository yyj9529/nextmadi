import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./remove-from-queue";

mock.module("server-only", () => ({}));

const { removeFromQueue, RemoveFromQueueError } = await import(
  "./remove-from-queue"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

describe("removeFromQueue", () => {
  test("mints a user_id internal token and POSTs remove-from-queue", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return new Response(null, { status: 204 });
    };

    await removeFromQueue(
      { userId: "user-1", reviewCardId: "card-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const request = calls[0];
    const url = new URL(request.url);
    expect(url.pathname).toBe("/api/v1/review/card-1/remove-from-queue");
    expect(request.method).toBe("POST");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("throws RemoveFromQueueError with isNotFound on 404", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await removeFromQueue(
        { userId: "user-2", reviewCardId: "gone" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(RemoveFromQueueError);
    expect(
      (thrown as InstanceType<typeof RemoveFromQueueError>).isNotFound,
    ).toBe(true);
  });

  test("throws RemoveFromQueueError on other non-2xx", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "boom" }, { status: 502 });

    let thrown: unknown;
    try {
      await removeFromQueue(
        { userId: "user-3", reviewCardId: "card-3" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(RemoveFromQueueError);
    expect(
      (thrown as InstanceType<typeof RemoveFromQueueError>).isNotFound,
    ).toBe(false);
  });
});

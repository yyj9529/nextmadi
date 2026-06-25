import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./get-bookshelf-count";

mock.module("server-only", () => ({}));

const { getBookshelfCount, BookshelfCountError } = await import(
  "./get-bookshelf-count"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

describe("getBookshelfCount", () => {
  test("reads bookshelf_count from the dashboard endpoint with a user_id token", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json({ bookshelf_count: 47 }, { status: 200 });
    };

    const count = await getBookshelfCount(
      { userId: "user-1" },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(count).toBe(47);

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe("/api/v1/home/dashboard");
    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("defaults to 0 when the field is missing", async () => {
    const fetcher: FetchLike = async () => Response.json({}, { status: 200 });

    const count = await getBookshelfCount(
      { userId: "user-2" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(count).toBe(0);
  });

  test("throws BookshelfCountError on non-2xx", async () => {
    const fetcher: FetchLike = async () => new Response("", { status: 500 });

    let thrown: unknown;
    try {
      await getBookshelfCount(
        { userId: "user-3" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(BookshelfCountError);
  });
});

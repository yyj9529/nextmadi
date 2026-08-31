import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./account-deletion";

mock.module("server-only", () => ({}));

const { scheduleAccountDeletion, cancelAccountDeletion, AccountDeletionError } =
  await import("./account-deletion");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

const options = (fetcher: FetchLike) => ({
  backendBaseUrl: "http://backend.test",
  internalAuthSecret: SECRET,
  fetcher,
});

describe("scheduleAccountDeletion", () => {
  test("calls DELETE /me with a user_id token and no body", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return new Response(null, { status: 204 });
    };

    await scheduleAccountDeletion({ userId: "user-1" }, options(fetcher));

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/me");
    expect(calls[0].method).toBe("DELETE");
    expect(calls[0].body).toBeNull();

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("throws AccountDeletionError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 401 });

    let thrown: unknown;
    try {
      await scheduleAccountDeletion({ userId: "user-2" }, options(fetcher));
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(AccountDeletionError);
    expect((thrown as InstanceType<typeof AccountDeletionError>).status).toBe(
      401,
    );
  });

  test("refuses to call the backend without an internal auth secret", async () => {
    let called = false;
    const fetcher: FetchLike = async () => {
      called = true;
      return new Response(null, { status: 204 });
    };

    // 시크릿이 없으면 토큰을 못 만든다. 그대로 호출하면 백엔드가 401로 막겠지만, 여기서
    // 멈추는 편이 원인을 그대로 보여준다.
    await expect(
      scheduleAccountDeletion(
        { userId: "user-3" },
        {
          backendBaseUrl: "http://backend.test",
          fetcher,
          internalAuthSecret: "",
        },
      ),
    ).rejects.toThrow("INTERNAL_AUTH_SECRET is required");
    expect(called).toBe(false);
  });
});

describe("cancelAccountDeletion", () => {
  test("calls POST /me/cancel-deletion with a user_id token", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return new Response(null, { status: 204 });
    };

    await cancelAccountDeletion({ userId: "user-9" }, options(fetcher));

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/me/cancel-deletion");
    expect(calls[0].method).toBe("POST");

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-9");
  });

  test("throws AccountDeletionError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 500 });

    let thrown: unknown;
    try {
      await cancelAccountDeletion({ userId: "user-10" }, options(fetcher));
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(AccountDeletionError);
    expect((thrown as InstanceType<typeof AccountDeletionError>).status).toBe(
      500,
    );
  });
});

import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./email-provisioning";

mock.module("server-only", () => ({}));

const {
  EMAIL_PROVISIONING_SESSION_TOKEN,
  EmailProvisioningError,
  consumeVerificationToken,
  createVerificationToken,
  linkEmailIdentityByUserId,
  lookupEmailIdentity,
  resolveEmailIdentity,
} = await import("./email-provisioning");

const SECRET = "nextjs-email-provisioning-secret-0123456789";
const EXPIRES = "2026-08-28T00:00:00.000Z";

const IDENTITY = {
  userId: "user-1",
  email: "mia@example.com",
  displayName: "Mia",
  isOnboarded: false,
  createdUser: true,
  canceledScheduledDeletion: false,
  linkedToExistingUser: false,
};

function recordingFetcher(response: () => Response) {
  const calls: Request[] = [];
  const fetcher: FetchLike = async (request) => {
    calls.push(request);
    return response();
  };
  return { calls, fetcher };
}

function options(fetcher: FetchLike) {
  return {
    backendBaseUrl: "http://backend.test",
    internalAuthSecret: SECRET,
    fetcher,
  };
}

describe("createVerificationToken", () => {
  test("posts the record to the Spring Boot verification-token endpoint", async () => {
    const { calls, fetcher } = recordingFetcher(() =>
      Response.json({
        identifier: "mia@example.com",
        token: "hashed-token",
        expires: EXPIRES,
      }),
    );

    const result = await createVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token", expires: EXPIRES },
      options(fetcher),
    );

    expect(result.token).toBe("hashed-token");
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      "http://backend.test/api/v1/auth/email/verification-tokens",
    );
    expect(calls[0].method).toBe("POST");
    expect(await calls[0].json()).toEqual({
      identifier: "mia@example.com",
      token: "hashed-token",
      expires: EXPIRES,
    });
  });

  test("signs an internal token carrying the email provisioning session", async () => {
    const { calls, fetcher } = recordingFetcher(() =>
      Response.json({
        identifier: "mia@example.com",
        token: "hashed-token",
        expires: EXPIRES,
      }),
    );

    await createVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token", expires: EXPIRES },
      options(fetcher),
    );

    const header = calls[0].headers.get("x-internal-auth");
    expect(header).toBeTruthy();
    const { payload } = await jwtVerify(
      header as string,
      new TextEncoder().encode(SECRET),
    );
    expect(payload.session_token).toBe(EMAIL_PROVISIONING_SESSION_TOKEN);
    expect(payload.user_id).toBeUndefined();
  });

  test("throws on a backend error rather than reporting success", async () => {
    const { fetcher } = recordingFetcher(() =>
      Response.json(
        { error_code: "rate_limit_exceeded", retryable: true },
        { status: 429 },
      ),
    );

    const failure = createVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token", expires: EXPIRES },
      options(fetcher),
    );

    await expect(failure).rejects.toBeInstanceOf(EmailProvisioningError);
  });
});

describe("consumeVerificationToken", () => {
  test("returns the consumed record", async () => {
    const { calls, fetcher } = recordingFetcher(() =>
      Response.json({
        identifier: "mia@example.com",
        token: "hashed-token",
        expires: EXPIRES,
      }),
    );

    const result = await consumeVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token" },
      options(fetcher),
    );

    expect(result?.expires).toBe(EXPIRES);
    expect(calls[0].url).toBe(
      "http://backend.test/api/v1/auth/email/verification-tokens/consume",
    );
  });

  test("returns null when the token is gone", async () => {
    const { fetcher } = recordingFetcher(() =>
      Response.json({ error_code: "verification_token_not_found" }, { status: 404 }),
    );

    const result = await consumeVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token" },
      options(fetcher),
    );

    expect(result).toBeNull();
  });

  test("throws on an outage instead of collapsing it to null", async () => {
    // null would reach the user as "this link expired" — an outage blamed on them.
    const { fetcher } = recordingFetcher(() =>
      Response.json({ error_code: "internal_error" }, { status: 500 }),
    );

    const failure = consumeVerificationToken(
      { identifier: "mia@example.com", token: "hashed-token" },
      options(fetcher),
    );

    await expect(failure).rejects.toBeInstanceOf(EmailProvisioningError);
  });
});

describe("lookupEmailIdentity", () => {
  test("posts the address in the body, never the query string", async () => {
    const { calls, fetcher } = recordingFetcher(() => Response.json(IDENTITY));

    await lookupEmailIdentity("mia@example.com", options(fetcher));

    expect(calls[0].url).toBe(
      "http://backend.test/api/v1/auth/email/identity/lookup",
    );
    expect(calls[0].url).not.toContain("mia@example.com");
    expect(await calls[0].json()).toEqual({ email: "mia@example.com" });
  });

  test("returns null for an unknown address", async () => {
    const { fetcher } = recordingFetcher(() =>
      Response.json({ error_code: "email_identity_not_found" }, { status: 404 }),
    );

    expect(await lookupEmailIdentity("nobody@example.com", options(fetcher))).toBeNull();
  });
});

describe("resolveEmailIdentity", () => {
  test("returns the resolved identity", async () => {
    const { calls, fetcher } = recordingFetcher(() => Response.json(IDENTITY));

    const result = await resolveEmailIdentity("mia@example.com", options(fetcher));

    expect(result).toEqual(IDENTITY);
    expect(calls[0].url).toBe("http://backend.test/api/v1/auth/email/identity");
  });

  test("throws when the backend refuses", async () => {
    const { fetcher } = recordingFetcher(() =>
      Response.json({ error_code: "validation_failed" }, { status: 400 }),
    );

    await expect(
      resolveEmailIdentity("", options(fetcher)),
    ).rejects.toBeInstanceOf(EmailProvisioningError);
  });
});

describe("linkEmailIdentityByUserId", () => {
  test("posts the user id in snake_case as the backend expects", async () => {
    const { calls, fetcher } = recordingFetcher(() =>
      Response.json({ ...IDENTITY, createdUser: false, linkedToExistingUser: true }),
    );

    const result = await linkEmailIdentityByUserId("user-1", options(fetcher));

    expect(result.linkedToExistingUser).toBe(true);
    expect(calls[0].url).toBe(
      "http://backend.test/api/v1/auth/email/identity/link",
    );
    expect(await calls[0].json()).toEqual({ user_id: "user-1" });
  });
});

describe("configuration", () => {
  // bun loads .env for tests and this repo's .env defines both variables, so the fallback has to
  // be exercised with them explicitly removed. Reading ambient env here would make the test pass
  // in CI (no .env) and fail locally.
  async function withoutEnv<T>(name: string, run: () => Promise<T>): Promise<T> {
    const previous = process.env[name];
    delete process.env[name];
    try {
      return await run();
    } finally {
      if (previous !== undefined) {
        process.env[name] = previous;
      }
    }
  }

  test("refuses to call without a backend base url", async () => {
    const { fetcher } = recordingFetcher(() => Response.json(IDENTITY));

    await withoutEnv("PHRASELOG_BACKEND_BASE_URL", async () => {
      await expect(
        resolveEmailIdentity("mia@example.com", {
          internalAuthSecret: SECRET,
          fetcher,
        }),
      ).rejects.toThrow("PHRASELOG_BACKEND_BASE_URL is required");
    });
  });

  test("refuses to call without an internal auth secret", async () => {
    const { fetcher } = recordingFetcher(() => Response.json(IDENTITY));

    await withoutEnv("INTERNAL_AUTH_SECRET", async () => {
      await expect(
        resolveEmailIdentity("mia@example.com", {
          backendBaseUrl: "http://backend.test",
          fetcher,
        }),
      ).rejects.toThrow("INTERNAL_AUTH_SECRET is required");
    });
  });
});

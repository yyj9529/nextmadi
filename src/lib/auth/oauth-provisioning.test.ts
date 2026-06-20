import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type {
  FetchLike,
  OAuthProvisioningError as OAuthProvisioningErrorType,
} from "./oauth-provisioning";

mock.module("server-only", () => ({}));

const { OAUTH_PROVISIONING_SESSION_TOKEN, provisionOAuthIdentity } =
  await import("./oauth-provisioning");

const SECRET = "nextjs-oauth-provisioning-secret-0123456789";

describe("provisionOAuthIdentity", () => {
  test("posts OAuth identity data to the Spring Boot provisioning endpoint", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json({
        userId: "user-1",
        email: "new@example.com",
        displayName: "New User",
        isOnboarded: false,
        createdUser: true,
        canceledScheduledDeletion: false,
      });
    };

    const result = await provisionOAuthIdentity(
      {
        provider: "kakao",
        providerUserId: "kakao-1",
        providerEmail: "new@example.com",
        displayName: "New User",
      },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(result).toEqual({
      userId: "user-1",
      email: "new@example.com",
      displayName: "New User",
      isOnboarded: false,
      createdUser: true,
      canceledScheduledDeletion: false,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/auth/oauth/identity");
    expect(calls[0].headers.get("content-type")).toBe("application/json");
    expect(calls[0].headers.get("x-internal-auth")).toBeTruthy();
    expect(await calls[0].json()).toEqual({
      provider: "kakao",
      provider_user_id: "kakao-1",
      provider_email: "new@example.com",
      display_name: "New User",
    });
  });

  test("uses the dedicated OAuth provisioning session token for backend auth", async () => {
    let headerToken: string | null = null;
    const fetcher: FetchLike = async (request) => {
      headerToken = request.headers.get("x-internal-auth");
      return Response.json({
        userId: "user-1",
        email: "new@example.com",
        displayName: null,
        isOnboarded: false,
        createdUser: true,
        canceledScheduledDeletion: false,
      });
    };

    await provisionOAuthIdentity(
      {
        provider: "google",
        providerUserId: "google-1",
        providerEmail: "new@example.com",
      },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(headerToken).toBeTruthy();
    const { payload } = await jwtVerify(
      headerToken!,
      new TextEncoder().encode(SECRET),
      { algorithms: ["HS256"] },
    );
    expect(payload.session_token).toBe(OAUTH_PROVISIONING_SESSION_TOKEN);
  });

  test("maps Spring Boot account-link conflicts to a typed error", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          error_code: "account_link_required",
          user_message:
            "이미 가입된 계정이에요. 기존 로그인 방법이나 이메일 링크로 로그인해주세요.",
          developer_hint: "Same email exists with a different OAuth identity.",
          retryable: false,
          request_correlation_id: "corr-1",
        },
        { status: 409 },
      );

    await expect(
      provisionOAuthIdentity(
        {
          provider: "google",
          providerUserId: "google-2",
          providerEmail: "same@example.com",
        },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      ),
    ).rejects.toMatchObject({
      name: "OAuthProvisioningError",
      errorCode: "account_link_required",
      status: 409,
    } satisfies Partial<OAuthProvisioningErrorType>);
  });
});

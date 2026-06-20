import { beforeEach, describe, expect, mock, test } from "bun:test";

let signIn = mock(async () => undefined);
let signOut = mock(async () => undefined);

mock.module("next-auth/react", () => ({
  signIn: (...args: Parameters<typeof signIn>) => signIn(...args),
  signOut: (...args: Parameters<typeof signOut>) => signOut(...args),
}));

const {
  AUTH_COMPLETE_REDIRECT,
  getOAuthCallbackErrorMessage,
  getPostLoginRedirectPath,
  signInWithOAuthProvider,
  signOutToLanding,
} = await import("./oauth-client");

describe("OAuth client flow", () => {
  beforeEach(() => {
    signIn = mock(async () => undefined);
    signOut = mock(async () => undefined);
  });

  test("starts Google OAuth and returns to the auth completion router", async () => {
    await signInWithOAuthProvider("google");

    expect(signIn).toHaveBeenCalledWith("google", {
      redirectTo: AUTH_COMPLETE_REDIRECT,
    });
  });

  test("starts Kakao OAuth and returns to the auth completion router", async () => {
    await signInWithOAuthProvider("kakao");

    expect(signIn).toHaveBeenCalledWith("kakao", {
      redirectTo: AUTH_COMPLETE_REDIRECT,
    });
  });

  test("signs out to the landing page", async () => {
    await signOutToLanding();

    expect(signOut).toHaveBeenCalledWith({ redirectTo: "/" });
  });

  test("maps callback errors into S03 messages", () => {
    expect(
      getOAuthCallbackErrorMessage({
        callback_error: "account_link_required",
      }),
    ).toBe(
      "이미 가입된 계정이에요. 기존 로그인 방법이나 이메일 링크로 로그인해주세요.",
    );
    expect(
      getOAuthCallbackErrorMessage(new URLSearchParams("error=AccessDenied")),
    ).toBe("로그인을 완료하지 못했어요. 다시 시도해주세요.");
    expect(getOAuthCallbackErrorMessage({})).toBeNull();
  });

  test("routes users after OAuth completion by onboarding state", () => {
    expect(getPostLoginRedirectPath(true)).toBe("/home");
    expect(getPostLoginRedirectPath(false)).toBe("/welcome/coach");
    expect(getPostLoginRedirectPath(null)).toBe("/welcome/coach");
  });
});

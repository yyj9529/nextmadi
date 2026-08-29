import { beforeEach, describe, expect, mock, test } from "bun:test";

type SignInResult = { ok?: boolean; error?: string; url?: string } | undefined;

let signIn = mock(async (): Promise<SignInResult> => undefined);
let signOut = mock(async () => undefined);

mock.module("next-auth/react", () => ({
  signIn: (...args: Parameters<typeof signIn>) => signIn(...args),
  signOut: (...args: Parameters<typeof signOut>) => signOut(...args),
}));

const {
  AUTH_COMPLETE_REDIRECT,
  getOAuthCallbackErrorMessage,
  getPostLoginRedirectPath,
  sendEmailMagicLink,
  shouldPromptEmailRetry,
  signInWithOAuthProvider,
  signOutToLanding,
} = await import("./oauth-client");

describe("OAuth client flow", () => {
  beforeEach(() => {
    signIn = mock(async (): Promise<SignInResult> => undefined);
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

  test("requests a magic link without letting Auth.js take over the screen", async () => {
    signIn = mock(async () => ({
      ok: true,
      url: "http://localhost:3000/api/auth/verify-request",
    }));

    const outcome = await sendEmailMagicLink("woojoo@phraselog.app");

    expect(signIn).toHaveBeenCalledWith("nodemailer", {
      email: "woojoo@phraselog.app",
      redirect: false,
      redirectTo: AUTH_COMPLETE_REDIRECT,
    });
    expect(outcome).toEqual({ sent: true });
  });

  test("reports a send failure instead of the sent state", async () => {
    signIn = mock(async () => ({ ok: true, error: "EmailSignin" }));

    expect(await sendEmailMagicLink("woojoo@phraselog.app")).toEqual({
      sent: false,
      message: "이메일을 보내지 못했어요. 잠시 후 다시 시도해주세요.",
    });
  });

  test("reports a thrown signIn as a failure rather than propagating", async () => {
    // 호출부가 try/catch를 잊어도 성공 화면이 뜨지 않아야 한다.
    signIn = mock(async () => {
      throw new Error("network down");
    });

    expect(await sendEmailMagicLink("woojoo@phraselog.app")).toEqual({
      sent: false,
      message: "이메일을 보내지 못했어요. 잠시 후 다시 시도해주세요.",
    });
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
      getOAuthCallbackErrorMessage(new URLSearchParams("error=Verification")),
    ).toBe("링크가 만료됐어요. 아래에서 새 링크를 받아주세요.");
    expect(
      getOAuthCallbackErrorMessage(new URLSearchParams("error=EmailSignin")),
    ).toBe("이메일을 보내지 못했어요. 주소를 확인하고 다시 시도해주세요.");
    expect(
      getOAuthCallbackErrorMessage(new URLSearchParams("error=AccessDenied")),
    ).toBe("로그인을 완료하지 못했어요. 다시 시도해주세요.");
    expect(getOAuthCallbackErrorMessage({})).toBeNull();
  });

  test("opens the email form only for errors a new link can recover", () => {
    expect(
      shouldPromptEmailRetry(new URLSearchParams("error=Verification")),
    ).toBe(true);
    expect(
      shouldPromptEmailRetry(new URLSearchParams("error=EmailSignin")),
    ).toBe(true);
    expect(
      shouldPromptEmailRetry({ callback_error: "account_link_required" }),
    ).toBe(true);
    expect(
      shouldPromptEmailRetry(new URLSearchParams("error=AccessDenied")),
    ).toBe(false);
    expect(shouldPromptEmailRetry({})).toBe(false);
  });

  test("routes users after OAuth completion by onboarding state", () => {
    expect(getPostLoginRedirectPath(true)).toBe("/home");
    expect(getPostLoginRedirectPath(false)).toBe("/welcome/coach");
    expect(getPostLoginRedirectPath(null)).toBe("/welcome/coach");
  });
});

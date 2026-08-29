import { describe, expect, test } from "bun:test";

import {
  EMAIL_PROVIDER_ID,
  EMAIL_SEND_FAILED_MESSAGE,
  EMPTY_EMAIL_MESSAGE,
  MALFORMED_EMAIL_MESSAGE,
  interpretEmailSignInResponse,
  validateEmailInput,
} from "./email-signin";

describe("email magic link input validation", () => {
  test("rejects an empty or whitespace-only address without submitting", () => {
    expect(validateEmailInput("")).toEqual({
      valid: false,
      message: EMPTY_EMAIL_MESSAGE,
    });
    expect(validateEmailInput("   ")).toEqual({
      valid: false,
      message: EMPTY_EMAIL_MESSAGE,
    });
  });

  test("rejects a malformed address", () => {
    for (const raw of ["woojoo", "woojoo@", "@phraselog.app", "a b@c.com"]) {
      expect(validateEmailInput(raw)).toEqual({
        valid: false,
        message: MALFORMED_EMAIL_MESSAGE,
      });
    }
  });

  test("normalizes case and surrounding whitespace like the backend does", () => {
    // 백엔드가 provider_user_id를 소문자로 저장하므로 클라이언트도 같은 형태로 보낸다.
    // 어긋나면 같은 사람이 두 개의 미소비 토큰을 갖게 된다.
    expect(validateEmailInput("  WooJoo@PhraseLog.App  ")).toEqual({
      valid: true,
      email: "woojoo@phraselog.app",
    });
  });
});

describe("email magic link send outcome", () => {
  test("treats a response without an error as sent", () => {
    expect(
      interpretEmailSignInResponse({
        ok: true,
        error: undefined,
        url: "http://localhost:3000/api/auth/verify-request",
      }),
    ).toEqual({ sent: true });
  });

  test("treats an Auth.js error code as a failure, not a send", () => {
    expect(
      interpretEmailSignInResponse({ ok: true, error: "EmailSignin" }),
    ).toEqual({ sent: false, message: EMAIL_SEND_FAILED_MESSAGE });
  });

  test("treats a non-ok response as a failure", () => {
    expect(interpretEmailSignInResponse({ ok: false })).toEqual({
      sent: false,
      message: EMAIL_SEND_FAILED_MESSAGE,
    });
  });

  test("treats a missing response as a failure rather than a silent success", () => {
    // signIn은 provider가 없거나 조회에 실패하면 아무것도 반환하지 않는다.
    // 이걸 성공으로 접으면 오지 않을 메일을 기다리는 화면이 뜬다.
    expect(interpretEmailSignInResponse(undefined)).toEqual({
      sent: false,
      message: EMAIL_SEND_FAILED_MESSAGE,
    });
    expect(interpretEmailSignInResponse(null)).toEqual({
      sent: false,
      message: EMAIL_SEND_FAILED_MESSAGE,
    });
  });

  test("pins the provider id Auth.js registers the Nodemailer provider under", () => {
    expect(EMAIL_PROVIDER_ID).toBe("nodemailer");
  });
});

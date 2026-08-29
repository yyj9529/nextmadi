"use client";

import { signIn, signOut } from "next-auth/react";

export {
  AUTH_COMPLETE_REDIRECT,
  getOAuthCallbackErrorMessage,
  getPostLoginRedirectPath,
  shouldPromptEmailRetry,
} from "./oauth-flow";
import {
  AUTH_COMPLETE_REDIRECT,
  type OAuthProvider,
} from "./oauth-flow";
import {
  EMAIL_PROVIDER_ID,
  type EmailSignInOutcome,
  interpretEmailSignInResponse,
} from "./email-signin";

export async function signInWithOAuthProvider(provider: OAuthProvider) {
  await signIn(provider, { redirectTo: AUTH_COMPLETE_REDIRECT });
}

/**
 * 매직링크 발송을 요청한다. `redirect: false`로 두는 이유는 발송 결과를 화면에서 봐야
 * 하기 때문이다 — Auth.js 기본 동작은 성공하든 실패하든 자기 페이지로 넘겨버려서
 * "이메일을 확인해주세요" 상태를 우리가 통제할 수 없다.
 *
 * 던지는 예외(네트워크 단절 등)도 실패로 접어서 돌려준다. 호출부가 try/catch를 잊으면
 * 성공 화면이 뜨는 구조를 만들지 않는다.
 */
export async function sendEmailMagicLink(
  email: string,
): Promise<EmailSignInOutcome> {
  try {
    const response = await signIn(EMAIL_PROVIDER_ID, {
      email,
      redirect: false,
      redirectTo: AUTH_COMPLETE_REDIRECT,
    });

    return interpretEmailSignInResponse(response);
  } catch {
    return interpretEmailSignInResponse(null);
  }
}

export async function signOutToLanding() {
  await signOut({ redirectTo: "/" });
}

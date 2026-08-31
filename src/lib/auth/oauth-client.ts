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

/**
 * 세션을 끊고 S01로 보낸다.
 *
 * `redirectTo`를 열어둔 이유는 계정 삭제(#24) 때문이다. 삭제 예약 뒤 보여줄 안내는
 * 로그아웃 네비게이션을 건너뛰고 살아남아야 하는데, 컴포넌트 state는 그 지점에서 사라진다.
 * 도착 URL에 실어 보내면 S01이 그것만 보고 렌더할 수 있고, 새로고침 한 번이면 사라진다.
 */
export async function signOutToLanding(redirectTo = "/") {
  await signOut({ redirectTo });
}

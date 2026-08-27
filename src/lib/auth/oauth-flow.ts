export const AUTH_COMPLETE_REDIRECT = "/auth/complete";

export type OAuthProvider = "google" | "kakao";

export type OAuthCallbackSearchParams =
  | URLSearchParams
  | Record<string, string | string[] | undefined>
  | null
  | undefined;

/**
 * Auth.js가 `pages.error: "/login"`으로 되돌려 보내는 코드와, BFF가 직접 붙이는
 * `callback_error` 코드를 한 곳에서 문구로 옮긴다.
 *
 * `Verification`은 토큰이 만료됐거나 이미 한 번 쓰인 경우다. 두 상황을 구분해서 알려줄 수
 * 없다 — 소비된 토큰과 만료된 토큰 모두 백엔드에서 사라지므로 Auth.js가 보는 것은 "없음"
 * 하나뿐이다. 어느 쪽이든 사용자가 할 일은 같아서(새 링크 요청) 문구를 합친다.
 */
const OAUTH_ERROR_MESSAGES: Record<string, string> = {
  // 이메일 매직링크(#19)가 생겼으므로 S03 엣지 케이스의 원래 문구로 되돌린다.
  // 메일함 소유가 증명되는 경로라서 같은 주소로 로그인하는 것이 허용된다.
  account_link_required:
    "이미 가입된 계정이에요. 기존 로그인 방법이나 이메일 링크로 로그인해주세요.",
  Verification: "링크가 만료됐어요. 아래에서 새 링크를 받아주세요.",
  EmailSignin: "이메일을 보내지 못했어요. 주소를 확인하고 다시 시도해주세요.",
};

/** 이 코드로 돌아왔을 때는 이메일 폼을 펼쳐둔다 — 재요청이 곧 복구 수단이기 때문이다. */
const EMAIL_RETRY_ERROR_CODES = new Set([
  "Verification",
  "EmailSignin",
  "account_link_required",
]);

const DEFAULT_OAUTH_ERROR_MESSAGE =
  "로그인을 완료하지 못했어요. 다시 시도해주세요.";

export function getOAuthCallbackErrorMessage(
  searchParams: OAuthCallbackSearchParams,
): string | null {
  const errorCode =
    getSearchParam(searchParams, "callback_error") ??
    getSearchParam(searchParams, "error");

  if (!errorCode) {
    return null;
  }

  return OAUTH_ERROR_MESSAGES[errorCode] ?? DEFAULT_OAUTH_ERROR_MESSAGE;
}

/**
 * 만료/발송 실패로 되돌아온 경우 이메일 폼을 펼친 상태로 렌더할지.
 * S03의 "링크가 만료됐어요 + 새 링크 요청"에서 요청 수단이 곧 이메일 폼이다.
 */
export function shouldPromptEmailRetry(
  searchParams: OAuthCallbackSearchParams,
): boolean {
  const errorCode =
    getSearchParam(searchParams, "callback_error") ??
    getSearchParam(searchParams, "error");

  return errorCode ? EMAIL_RETRY_ERROR_CODES.has(errorCode) : false;
}

export function getPostLoginRedirectPath(
  isOnboarded: boolean | null | undefined,
): "/home" | "/welcome/coach" {
  return isOnboarded ? "/home" : "/welcome/coach";
}

function getSearchParam(
  searchParams: OAuthCallbackSearchParams,
  key: string,
): string | undefined {
  if (!searchParams) {
    return undefined;
  }

  if (searchParams instanceof URLSearchParams) {
    return searchParams.get(key) ?? undefined;
  }

  const value = searchParams[key];
  return Array.isArray(value) ? value[0] : value;
}

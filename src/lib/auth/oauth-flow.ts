export const AUTH_COMPLETE_REDIRECT = "/auth/complete";

export type OAuthProvider = "google" | "kakao";

export type OAuthCallbackSearchParams =
  | URLSearchParams
  | Record<string, string | string[] | undefined>
  | null
  | undefined;

const OAUTH_ERROR_MESSAGES: Record<string, string> = {
  // 이메일 로그인은 아직 미구현(#19)이라 안내하지 않는다. 현재 지원하는 방법(Google/Kakao)만 제시한다.
  account_link_required:
    "이미 가입된 계정이에요. 처음 가입할 때 사용한 방법(Google 또는 카카오)으로 로그인해주세요.",
};

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

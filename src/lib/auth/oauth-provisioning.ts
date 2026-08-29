import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

export const OAUTH_PROVISIONING_SESSION_TOKEN = "__oauth_provisioning__";

export type OAuthProvider = "google" | "kakao";

export type ProvisionOAuthIdentityInput = {
  provider: OAuthProvider;
  providerUserId: string;
  providerEmail: string;
  displayName?: string | null;
  /**
   * provider가 이 주소를 검증했다고 말하는가. 백엔드가 users.email에 쓸지를 여기서 가른다.
   *
   * users.email은 매직링크 로그인이 기존 계정을 찾는 열쇠다(S03 same-email 케이스). 그 열은
   * 검증된 주소만 담아야 한다 — 아니면 남의 주소를 주장해 만든 계정이, 그 주소의 진짜 주인이
   * 매직링크로 로그인할 때 그 사람을 받아버린다.
   */
  providerEmailVerified: boolean;
};

export type ProvisionedOAuthIdentity = {
  userId: string;
  email: string;
  displayName: string | null;
  isOnboarded: boolean;
  createdUser: boolean;
  canceledScheduledDeletion: boolean;
};

export type ProvisionOAuthIdentityOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  /** 테스트에서만 줄인다. 기본값은 BACKEND_TIMEOUT_MS. */
  timeoutMs?: number;
};

export type FetchLike = (request: Request) => Promise<Response>;

/**
 * 백엔드 왕복 제한 시간. 로그인 요청 하나가 매달려 있을 수 있는 시간이고, Vercel 함수의
 * 예산도 같이 갉아먹는다. Spring Boot가 반쯤 열린 채(TCP는 붙었는데 응답이 없는) 있을 때
 * 기본 소켓 타임아웃까지 기다리면 사용자는 실패도 성공도 아닌 화면을 본다.
 */
const BACKEND_TIMEOUT_MS = 5_000;

type ApiErrorBody = {
  error_code?: string;
  user_message?: string;
  developer_hint?: string;
  retryable?: boolean;
};

export class OAuthProvisioningError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly userMessage?: string;
  readonly developerHint?: string;
  readonly retryable?: boolean;

  constructor(status: number, body: ApiErrorBody) {
    super(body.user_message ?? body.error_code ?? "OAuth provisioning failed");
    this.name = "OAuthProvisioningError";
    this.status = status;
    this.errorCode = body.error_code ?? "oauth_provisioning_failed";
    this.userMessage = body.user_message;
    this.developerHint = body.developer_hint;
    this.retryable = body.retryable;
  }
}

export async function provisionOAuthIdentity(
  input: ProvisionOAuthIdentityInput,
  options: ProvisionOAuthIdentityOptions = {},
): Promise<ProvisionedOAuthIdentity> {
  const backendBaseUrl =
    options.backendBaseUrl ?? process.env.PHRASELOG_BACKEND_BASE_URL;
  const internalAuthSecret =
    options.internalAuthSecret ?? process.env.INTERNAL_AUTH_SECRET;
  const fetcher: FetchLike = options.fetcher ?? ((request) => fetch(request));

  if (!backendBaseUrl) {
    throw new Error("PHRASELOG_BACKEND_BASE_URL is required");
  }

  if (!internalAuthSecret) {
    throw new Error("INTERNAL_AUTH_SECRET is required");
  }

  const internalAuthToken = await mintInternalAuthToken(
    { sessionToken: OAUTH_PROVISIONING_SESSION_TOKEN },
    internalAuthSecret,
  );

  const response = await fetcher(
    new Request(
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1/auth/oauth/identity`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-auth": internalAuthToken,
        },
        body: JSON.stringify({
          provider: input.provider,
          provider_user_id: input.providerUserId,
          provider_email: input.providerEmail,
          display_name: input.displayName ?? null,
          provider_email_verified: input.providerEmailVerified,
        }),
        signal: AbortSignal.timeout(options.timeoutMs ?? BACKEND_TIMEOUT_MS),
      },
    ),
  );

  if (!response.ok) {
    throw new OAuthProvisioningError(response.status, await readError(response));
  }

  return (await response.json()) as ProvisionedOAuthIdentity;
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { error_code: "oauth_provisioning_failed" };
  }
}

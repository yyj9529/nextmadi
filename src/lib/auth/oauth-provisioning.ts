import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

export const OAUTH_PROVISIONING_SESSION_TOKEN = "__oauth_provisioning__";

export type OAuthProvider = "google" | "kakao";

export type ProvisionOAuthIdentityInput = {
  provider: OAuthProvider;
  providerUserId: string;
  providerEmail: string;
  displayName?: string | null;
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
};

export type FetchLike = (request: Request) => Promise<Response>;

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
        }),
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

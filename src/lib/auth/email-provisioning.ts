import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// 이메일 매직링크 전용 내부 통행증. OAuth(#18)와 분리해 한쪽이 새도 다른 쪽 권한이 딸려가지 않게 한다.
// 백엔드 EmailProvisioning.SESSION_TOKEN과 같은 값이어야 한다.
export const EMAIL_PROVISIONING_SESSION_TOKEN = "__email_provisioning__";

export type FetchLike = (request: Request) => Promise<Response>;

export type EmailProvisioningOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
};

export type EmailIdentity = {
  userId: string;
  email: string;
  displayName: string | null;
  isOnboarded: boolean;
  createdUser: boolean;
  canceledScheduledDeletion: boolean;
  linkedToExistingUser: boolean;
};

export type VerificationTokenRecord = {
  identifier: string;
  token: string;
  /** ISO-8601. Auth.js가 Date로 다루므로 어댑터 경계에서 변환한다. */
  expires: string;
};

type ApiErrorBody = {
  error_code?: string;
  user_message?: string;
  developer_hint?: string;
  retryable?: boolean;
};

export class EmailProvisioningError extends Error {
  readonly status: number;
  readonly errorCode: string;
  readonly userMessage?: string;
  readonly developerHint?: string;
  readonly retryable?: boolean;

  constructor(status: number, body: ApiErrorBody) {
    super(body.user_message ?? body.error_code ?? "Email provisioning failed");
    this.name = "EmailProvisioningError";
    this.status = status;
    this.errorCode = body.error_code ?? "email_provisioning_failed";
    this.userMessage = body.user_message;
    this.developerHint = body.developer_hint;
    this.retryable = body.retryable;
  }
}

export async function createVerificationToken(
  record: VerificationTokenRecord,
  options: EmailProvisioningOptions = {},
): Promise<VerificationTokenRecord> {
  return requireOk<VerificationTokenRecord>(
    await post("/auth/email/verification-tokens", record, options),
  );
}

/**
 * 토큰을 소비한다. 없으면 null — Auth.js의 "not found" 규약이다.
 *
 * 404만 null로 옮기고 나머지 실패는 던진다. 백엔드 장애를 null로 뭉개면 Auth.js가 "만료된 링크"로
 * 표시해, 장애가 사용자 잘못처럼 보인다.
 */
// 이름이 use*가 아니어야 한다 — eslint react-hooks 규칙이 React Hook 호출로 오인한다.
// Auth.js가 요구하는 어댑터 메서드 이름 useVerificationToken은 bff-adapter.ts에서 유지된다.
export async function consumeVerificationToken(
  input: { identifier: string; token: string },
  options: EmailProvisioningOptions = {},
): Promise<VerificationTokenRecord | null> {
  return nullOn404<VerificationTokenRecord>(
    await post("/auth/email/verification-tokens/consume", input, options),
  );
}

/** 읽기 전용. 링크를 보내는 시점에 호출되므로 절대 사용자를 만들지 않는다. */
export async function lookupEmailIdentity(
  email: string,
  options: EmailProvisioningOptions = {},
): Promise<EmailIdentity | null> {
  return nullOn404<EmailIdentity>(
    await post("/auth/email/identity/lookup", { email }, options),
  );
}

/** 링크가 검증된 뒤에만 호출한다. 사용자를 만들거나 기존 계정에 연결한다. */
export async function resolveEmailIdentity(
  email: string,
  options: EmailProvisioningOptions = {},
): Promise<EmailIdentity> {
  return requireOk<EmailIdentity>(
    await post("/auth/email/identity", { email }, options),
  );
}

/** Auth.js updateUser 대응 — 주소가 아니라 user id로 이메일 신분증을 보장한다. */
export async function linkEmailIdentityByUserId(
  userId: string,
  options: EmailProvisioningOptions = {},
): Promise<EmailIdentity> {
  return requireOk<EmailIdentity>(
    await post("/auth/email/identity/link", { user_id: userId }, options),
  );
}

async function post(
  path: string,
  body: unknown,
  options: EmailProvisioningOptions,
): Promise<Response> {
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
    { sessionToken: EMAIL_PROVISIONING_SESSION_TOKEN },
    internalAuthSecret,
  );

  return fetcher(
    new Request(
      `${backendBaseUrl.replace(/\/+$/, "")}/api/v1${path}`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-internal-auth": internalAuthToken,
        },
        body: JSON.stringify(body),
      },
    ),
  );
}

async function requireOk<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new EmailProvisioningError(response.status, await readError(response));
  }
  return (await response.json()) as T;
}

async function nullOn404<T>(response: Response): Promise<T | null> {
  if (response.status === 404) {
    return null;
  }
  return requireOk<T>(response);
}

async function readError(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { error_code: "email_provisioning_failed" };
  }
}

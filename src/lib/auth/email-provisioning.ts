import "server-only";

import { mintInternalAuthToken } from "../internal-auth";

// 이메일 매직링크 전용 내부 통행증. OAuth(#18)와 분리해 한쪽이 새도 다른 쪽 권한이 딸려가지 않게 한다.
// 백엔드 EmailProvisioning.SESSION_TOKEN과 같은 값이어야 한다.
export const EMAIL_PROVISIONING_SESSION_TOKEN = "__email_provisioning__";

export type FetchLike = (request: Request) => Promise<Response>;

/**
 * 백엔드 왕복 제한 시간. 로그인 요청 하나가 매달려 있을 수 있는 시간이고, Vercel 함수의
 * 예산도 같이 갉아먹는다. Spring Boot가 반쯤 열린 채(TCP는 붙었는데 응답이 없는) 있을 때
 * 기본 소켓 타임아웃까지 기다리면 사용자는 실패도 성공도 아닌 화면을 본다.
 */
const BACKEND_TIMEOUT_MS = 5_000;

export type EmailProvisioningOptions = {
  backendBaseUrl?: string;
  internalAuthSecret?: string;
  fetcher?: FetchLike;
  /** 테스트에서만 줄인다. 기본값은 BACKEND_TIMEOUT_MS. */
  timeoutMs?: number;
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

export type SendQuota = {
  identifier: string;
  remaining: number;
};

/**
 * 이 주소로 링크를 하나 더 보내도 되는지 묻는다. 상한에 닿았으면 429로 던진다.
 *
 * **발송 전에** 불러야 한다. Auth.js는 발송과 토큰 저장을 동시에 시작하므로
 * (`@auth/core/lib/actions/signin/send-token.js`), 저장 시점에 거절하면 메일은 이미 나간 뒤다 —
 * 할당량은 그대로 쓰고 수신자는 저장된 행이 없는 죽은 링크를 받는다. 그 배치의 상한은 상한이
 * 없느니만 못하다.
 */
export async function checkSendQuota(
  identifier: string,
  options: EmailProvisioningOptions = {},
): Promise<SendQuota> {
  return requireOk<SendQuota>(
    await post("/auth/email/verification-tokens/quota", { identifier }, options),
  );
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
    "verification_token_not_found",
  );
}

/** 읽기 전용. 링크를 보내는 시점에 호출되므로 절대 사용자를 만들지 않는다. */
export async function lookupEmailIdentity(
  email: string,
  options: EmailProvisioningOptions = {},
): Promise<EmailIdentity | null> {
  return nullOn404<EmailIdentity>(
    await post("/auth/email/identity/lookup", { email }, options),
    "email_identity_not_found",
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
        signal: AbortSignal.timeout(options.timeoutMs ?? BACKEND_TIMEOUT_MS),
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

/**
 * "없음"만 null로 옮긴다.
 *
 * 상태 코드만 보면 부족하다 — 배포되지 않은 컨트롤러, 잘못된 base path, 프록시도 404를 준다.
 * 그것까지 null이 되면 Auth.js가 "만료된 링크"를 띄워, 우리 장애가 사용자 잘못처럼 보인다.
 * 그래서 우리 에러 계약이 실어 보내는 error_code까지 확인한다. V009 롤백 절차가 앱을 먼저
 * 내리라고 말하는 그 구간이 정확히 이 상황이다.
 */
async function nullOn404<T>(
  response: Response,
  notFoundCode: string,
): Promise<T | null> {
  if (response.status === 404) {
    const body = await readError(response.clone());
    if (body.error_code === notFoundCode) {
      return null;
    }
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

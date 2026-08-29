import NextAuth, {
  type NextAuthConfig,
  type Profile,
  type User,
} from "next-auth";
import type { Adapter } from "next-auth/adapters";
import Google from "next-auth/providers/google";
import Kakao from "next-auth/providers/kakao";

import { createBffAdapter } from "./lib/auth/bff-adapter";
import { buildEmailProvider } from "./lib/auth/email-provider";
import { EMAIL_PROVIDER_ID } from "./lib/auth/email-signin";
import {
  OAuthProvisioningError,
  type OAuthProvider,
  type ProvisionedOAuthIdentity,
  provisionOAuthIdentity as provisionOAuthIdentityDefault,
} from "./lib/auth/oauth-provisioning";

export const AUTH_SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;
const AUTH_SESSION_UPDATE_AGE_SECONDS = 24 * 60 * 60;
const OAUTH_PROVIDERS = new Set<OAuthProvider>(["google", "kakao"]);

/**
 * Auth.js Nodemailer provider의 고정 id. account.provider 분기에 쓴다.
 * 정의는 `lib/auth/email-signin`에 있다 — 로그인 화면(클라이언트)이 같은 값으로
 * `signIn(...)`을 호출하므로 서버 전용 모듈에 둘 수 없다.
 */
export { EMAIL_PROVIDER_ID };

/**
 * OAuth 콜백 경로. 이 요청에서만 어댑터와 이메일 provider를 뺀다 — 이유는 아래 참조.
 * signin/* 은 provider로 리다이렉트만 하므로 어댑터를 건드리지 않는다.
 */
const OAUTH_CALLBACK_PATH = /\/callback\/(google|kakao)(?:\/|$)/;

export function isOAuthCallbackRequest(request?: Request): boolean {
  if (!request) {
    return false;
  }

  try {
    return OAUTH_CALLBACK_PATH.test(new URL(request.url).pathname);
  } catch {
    // URL이 파싱되지 않으면 어댑터를 붙인 쪽(기본값)으로 둔다. 이메일 로그인이 깨지는 편이
    // 조용히 OAuth 프로비저닝을 건너뛰는 것보다 낫다.
    return false;
  }
}

type BuildAuthConfigOptions = {
  secureCookies?: boolean;
  /**
   * 이메일 provider와 어댑터를 포함할지. false면 #19 이전과 정확히 같은 설정이 된다.
   *
   * Auth.js의 adapter는 provider별로 범위가 잡히지 않는다. 어댑터가 존재하기만 하면
   * OAuth 콜백도 `getUserByAccount` → `linkAccount` → `getUserByEmail`을 어댑터에 묻는다
   * (@auth/core/lib/actions/callback/index.js:56, handle-login.js:175/230/264). 우리 어댑터는
   * 이메일 경로만 구현했으므로 그 호출들이 던지고, Google/Kakao 로그인이 전부 실패한다.
   * #19 이전에 OAuth를 지켜준 건 handle-login.js:24의 `if (!adapter)` 조기 반환이었다.
   *
   * 어댑터에 OAuth 면을 구현해 넣는 대신 콜백 요청에서만 떼기로 했다. #18의 signIn 콜백
   * 프로비저닝을 한 줄도 건드리지 않고, 두 경로가 서로를 깨뜨릴 수 없게 된다.
   */
  includeEmailProvider?: boolean;
  provisionOAuthIdentity?: typeof provisionOAuthIdentityDefault;
  /** 테스트에서 BFF 어댑터를 대체한다. 기본값은 Spring Boot를 호출하는 실제 어댑터. */
  adapter?: Adapter;
  /** 테스트에서 provider를 대체한다. 기본값은 env로 SES/개발 모드를 고르는 실제 provider. */
  emailProvider?: NextAuthConfig["providers"][number];
};

type PhraseLogOAuthUser = User & {
  phraselogUserId?: string;
  isOnboarded?: boolean;
};

type PhraseLogAdapterUser = User & { isOnboarded?: boolean };

export function buildAuthConfig(
  options: BuildAuthConfigOptions = {},
): NextAuthConfig {
  const secureCookies =
    options.secureCookies ?? process.env.NODE_ENV === "production";
  const provisionIdentity =
    options.provisionOAuthIdentity ?? provisionOAuthIdentityDefault;
  const includeEmail = options.includeEmailProvider ?? true;
  const adapter = options.adapter ?? createBffAdapter();
  const emailProvider = options.emailProvider ?? buildEmailProvider();

  return {
    // 어댑터는 이메일 provider가 요구해서 존재한다. 저장은 여전히 Spring Boot가 한다 (ADR-010) —
    // 어댑터 메서드가 X-Internal-Auth로 백엔드를 호출할 뿐, Next.js는 DB에 접근하지 않는다.
    //
    // 둘은 항상 같이 있거나 같이 없어야 한다. 이메일 provider만 남기면 Auth.js가 설정 검증에서
    // MissingAdapter("Email login requires an adapter")로 실패한다 (@auth/core/lib/utils/assert.js:135).
    ...(includeEmail ? { adapter } : {}),
    providers: includeEmail ? [Google, Kakao, emailProvider] : [Google, Kakao],
    pages: {
      signIn: "/login",
      error: "/login",
    },
    session: {
      strategy: "jwt",
      maxAge: AUTH_SESSION_MAX_AGE_SECONDS,
      updateAge: AUTH_SESSION_UPDATE_AGE_SECONDS,
    },
    useSecureCookies: secureCookies,
    cookies: {
      sessionToken: {
        name: secureCookies
          ? "__Host-authjs.session-token"
          : "authjs.session-token",
        options: {
          httpOnly: true,
          sameSite: "lax",
          path: "/",
          secure: secureCookies,
        },
      },
    },
    callbacks: {
      async signIn({ user, account, profile }) {
        if (!account || !isOAuthProvider(account.provider)) {
          return true;
        }

        // provider가 "이 주소는 검증 안 됐다"고 말하면 여기서 멈춘다. users.email은 매직링크가
        // 기존 계정을 찾는 열쇠라서(S03 same-email 케이스), 검증되지 않은 주소가 그 열에
        // 들어가면 남의 주소를 주장해 만든 계정이 진짜 주인을 받아버린다. 백엔드도 같은 값을
        // 거절하므로 한쪽만 배포돼도 뚫리지 않는다.
        if (providerEmailVerified(profile) === false) {
          return "/login?callback_error=email_unverified";
        }

        try {
          attachPhraseLogIdentity(
            user,
            await provisionIdentity({
              provider: account.provider,
              providerUserId: account.providerAccountId,
              providerEmail: user.email ?? stringValue(profile?.email) ?? "",
              displayName: user.name ?? stringValue(profile?.name) ?? null,
              providerEmailVerified: providerEmailVerified(profile) !== false,
            }),
          );
          return true;
        } catch (error) {
          if (
            error instanceof OAuthProvisioningError &&
            error.errorCode === "account_link_required"
          ) {
            return "/login?callback_error=account_link_required";
          }

          // 이 catch는 백엔드 미기동, 네트워크 장애, 5xx를 전부 같은 한 줄로 뭉갠다. 로그가
          // 없으면 화면에는 "다시 시도해주세요"만 남아서 원인을 알아낼 방법이 사라진다.
          // 실제로 이 침묵 때문에 로컬 로그인 장애를 쿠키 문제로 오진한 적이 있다
          // (#36 exec-plan의 "NextAuth 세션 쿠키 위조 실패" 기록).
          // 이메일과 provider 계정 id는 찍지 않는다 — SECURITY.md 로깅 원칙.
          console.error(
            `[auth] OAuth provisioning failed for ${account.provider}:`,
            error instanceof OAuthProvisioningError
              ? `status=${error.status} error_code=${error.errorCode}`
              : error,
          );

          return "/login?callback_error=oauth";
        }
      },
      async jwt({ token, user, account }) {
        if (account?.provider === EMAIL_PROVIDER_ID && user) {
          // 이메일 경로에서는 어댑터가 이미 Spring Boot에서 사용자를 확정했으므로 user.id가
          // PhraseLog user_id다. OAuth처럼 signIn 콜백에서 프로비저닝할 것이 없다.
          const adapterUser = user as PhraseLogAdapterUser;
          token.phraselogUserId = adapterUser.id;
          token.isOnboarded = adapterUser.isOnboarded ?? false;
          token.email = adapterUser.email ?? token.email;
          token.name = adapterUser.name ?? token.name;
          return token;
        }

        if (account && isOAuthProvider(account.provider) && user) {
          const phraseLogUser = user as PhraseLogOAuthUser;
          token.phraselogUserId = phraseLogUser.phraselogUserId;
          token.isOnboarded = phraseLogUser.isOnboarded ?? false;
          token.email = phraseLogUser.email ?? token.email;
          token.name = phraseLogUser.name ?? token.name;
        }

        return token;
      },
      async session({ session, token }) {
        if (session.user && token.phraselogUserId) {
          const sessionUser = session.user as typeof session.user & {
            id?: string;
            isOnboarded?: boolean;
          };
          sessionUser.id = token.phraselogUserId;
          const email = stringValue(token.email);
          const name = stringValue(token.name);
          if (email) {
            sessionUser.email = email;
          }
          if (name) {
            sessionUser.name = name;
          }
          sessionUser.isOnboarded = Boolean(token.isOnboarded);
        }

        return session;
      },
    },
  };
}

export const authConfig = buildAuthConfig();

/** OAuth 콜백 전용 설정 — 어댑터도 이메일 provider도 없는, #19 이전과 동일한 모양. */
const oauthCallbackConfig = buildAuthConfig({ includeEmailProvider: false });

/**
 * 요청별 설정 선택. next-auth v5는 `NextAuth(async (req) => config)`를 지원하고
 * (next-auth/index.js:102) 라우트 핸들러에만 req를 넘긴다. auth()/signIn()/signOut()은
 * req 없이 호출되므로 전체 설정을 받는다 — 이들은 어댑터를 쓰지 않으니 문제되지 않는다.
 *
 * 별도 함수로 내보내는 이유는 테스트가 이 선택 자체를 검증할 수 있어야 하기 때문이다.
 * 설정 객체의 모양만 보는 테스트로는 어댑터가 OAuth 콜백을 깨뜨리는 것을 잡을 수 없었다.
 */
export function selectAuthConfig(request?: Request): NextAuthConfig {
  return isOAuthCallbackRequest(request) ? oauthCallbackConfig : authConfig;
}

export const { handlers, auth, signIn, signOut } = NextAuth(selectAuthConfig);

function isOAuthProvider(provider: string): provider is OAuthProvider {
  return OAUTH_PROVIDERS.has(provider as OAuthProvider);
}

function attachPhraseLogIdentity(
  user: User,
  provisioned: ProvisionedOAuthIdentity,
) {
  const phraseLogUser = user as PhraseLogOAuthUser;
  phraseLogUser.id = provisioned.userId;
  phraseLogUser.phraselogUserId = provisioned.userId;
  phraseLogUser.email = provisioned.email;
  phraseLogUser.name = provisioned.displayName ?? phraseLogUser.name;
  phraseLogUser.isOnboarded = provisioned.isOnboarded;
}

/**
 * provider가 이메일 검증 여부를 말했는가, 말했다면 무엇이라 했는가.
 *
 * - Google: OIDC 표준 `email_verified`.
 * - Kakao: `kakao_account.is_email_verified`. 카카오는 이메일이 미검증일 수 있어서 이 필드를
 *   따로 노출한다 — 이 함수가 존재하는 실질적 이유다.
 *
 * 세 상태를 구분한다: true(검증됨), false(명시적 미검증), undefined(provider가 말하지 않음).
 * 말하지 않은 것을 미검증으로 취급하면 필드를 안 주는 provider가 전부 막히므로, 거절은
 * 명시적 false에만 한다.
 */
function providerEmailVerified(profile: Profile | undefined): boolean | undefined {
  if (!profile) {
    return undefined;
  }

  const googleClaim = profile.email_verified;
  if (typeof googleClaim === "boolean") {
    return googleClaim;
  }

  const kakaoAccount = (profile as { kakao_account?: unknown }).kakao_account;
  if (kakaoAccount && typeof kakaoAccount === "object") {
    const kakaoClaim = (kakaoAccount as { is_email_verified?: unknown })
      .is_email_verified;
    if (typeof kakaoClaim === "boolean") {
      return kakaoClaim;
    }
  }

  return undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

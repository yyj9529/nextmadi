import NextAuth, {
  type NextAuthConfig,
  type User,
} from "next-auth";
import Google from "next-auth/providers/google";
import Kakao from "next-auth/providers/kakao";

import {
  OAuthProvisioningError,
  type OAuthProvider,
  type ProvisionedOAuthIdentity,
  provisionOAuthIdentity as provisionOAuthIdentityDefault,
} from "./lib/auth/oauth-provisioning";

export const AUTH_SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;
const AUTH_SESSION_UPDATE_AGE_SECONDS = 24 * 60 * 60;
const OAUTH_PROVIDERS = new Set<OAuthProvider>(["google", "kakao"]);

type BuildAuthConfigOptions = {
  secureCookies?: boolean;
  provisionOAuthIdentity?: typeof provisionOAuthIdentityDefault;
};

type PhraseLogOAuthUser = User & {
  phraselogUserId?: string;
  isOnboarded?: boolean;
};

export function buildAuthConfig(
  options: BuildAuthConfigOptions = {},
): NextAuthConfig {
  const secureCookies =
    options.secureCookies ?? process.env.NODE_ENV === "production";
  const provisionIdentity =
    options.provisionOAuthIdentity ?? provisionOAuthIdentityDefault;

  return {
    providers: [Google, Kakao],
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

        try {
          attachPhraseLogIdentity(
            user,
            await provisionIdentity({
              provider: account.provider,
              providerUserId: account.providerAccountId,
              providerEmail: user.email ?? stringValue(profile?.email) ?? "",
              displayName: user.name ?? stringValue(profile?.name) ?? null,
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
export const { handlers, auth, signIn, signOut } = NextAuth(authConfig);

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

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

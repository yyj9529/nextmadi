import NextAuth, {
  type NextAuthConfig,
  type User,
} from "next-auth";
import type { Adapter } from "next-auth/adapters";
import Google from "next-auth/providers/google";
import Kakao from "next-auth/providers/kakao";

import { createBffAdapter } from "./lib/auth/bff-adapter";
import { buildEmailProvider } from "./lib/auth/email-provider";
import {
  OAuthProvisioningError,
  type OAuthProvider,
  type ProvisionedOAuthIdentity,
  provisionOAuthIdentity as provisionOAuthIdentityDefault,
} from "./lib/auth/oauth-provisioning";

export const AUTH_SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;
const AUTH_SESSION_UPDATE_AGE_SECONDS = 24 * 60 * 60;
const OAUTH_PROVIDERS = new Set<OAuthProvider>(["google", "kakao"]);

/** Auth.js Nodemailer provider의 고정 id. account.provider 분기에 쓴다. */
export const EMAIL_PROVIDER_ID = "nodemailer";

type BuildAuthConfigOptions = {
  secureCookies?: boolean;
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
  const adapter = options.adapter ?? createBffAdapter();
  const emailProvider = options.emailProvider ?? buildEmailProvider();

  return {
    // 어댑터는 이메일 provider가 요구해서 존재한다. 저장은 여전히 Spring Boot가 한다 (ADR-010) —
    // 어댑터 메서드가 X-Internal-Auth로 백엔드를 호출할 뿐, Next.js는 DB에 접근하지 않는다.
    adapter,
    providers: [Google, Kakao, emailProvider],
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

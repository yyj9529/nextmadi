import type { Metadata } from "next";

import { getOAuthCallbackErrorMessage } from "@/lib/auth/oauth-flow";

import { LoginExperience } from "./LoginExperience";

export const metadata: Metadata = {
  title: "로그인 / 가입",
};

// PPT 충실도 패스: s03_login.PNG. 실제 인증은 NextAuth가 담당
// (openapi.yaml 범위 밖). 목 로그인은 신규 가입 흐름을 보여주기 위해
// /welcome/coach(S03b)로 라우팅한다.
type LoginPageProps = {
  searchParams?:
    | Promise<Record<string, string | string[] | undefined>>
    | Record<string, string | string[] | undefined>;
};

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const resolvedSearchParams = await searchParams;

  return (
    <LoginExperience
      callbackErrorMessage={getOAuthCallbackErrorMessage(
        resolvedSearchParams,
      )}
    />
  );
}

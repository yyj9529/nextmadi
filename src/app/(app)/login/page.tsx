import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { auth } from "@/auth";
import {
  getOAuthCallbackErrorMessage,
  shouldPromptEmailRetry,
} from "@/lib/auth/oauth-flow";

import { LoginExperience } from "./LoginExperience";

export const metadata: Metadata = {
  title: "로그인 / 가입",
};

// PPT 충실도 패스: s03_login.PNG. 실제 인증은 NextAuth가 담당
// (openapi.yaml 범위 밖). 매직링크 만료(`?error=Verification`)로 돌아온 경우
// 이메일 폼을 펼친 채로 렌더해 "새 링크 요청"이 곧바로 가능하게 한다 (S03 엣지 케이스).
type LoginPageProps = {
  searchParams?:
    | Promise<Record<string, string | string[] | undefined>>
    | Record<string, string | string[] | undefined>;
};

export default async function LoginPage({ searchParams }: LoginPageProps) {
  // 이미 로그인된 사용자는 로그인 화면을 볼 이유가 없다 (S03 엣지 케이스, C2-07).
  const session = await auth();
  if (session?.user?.id) {
    redirect("/home");
  }

  const resolvedSearchParams = await searchParams;

  return (
    <LoginExperience
      callbackErrorMessage={getOAuthCallbackErrorMessage(
        resolvedSearchParams,
      )}
      promptEmailRetry={shouldPromptEmailRetry(resolvedSearchParams)}
    />
  );
}

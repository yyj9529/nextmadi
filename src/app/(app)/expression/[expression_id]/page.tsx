import type { Metadata } from "next";

import { requireAuthenticatedUserId } from "@/lib/auth/require-authenticated-user";
import { ExpressionDetailExperience } from "./ExpressionDetailExperience";

export const metadata: Metadata = {
  title: "표현 상세",
};

type ExpressionDetailPageProps = {
  params: Promise<{
    expression_id: string;
  }>;
};

// S09 표현 상세. 인증된 사용자만 진입하며, 상세/삭제/큐 제거는 BFF 라우트로 조회·변경한다. (#47)
// 데이터 페칭은 클라이언트 훅(useExpressionDetail)이 담당해 로딩/404/에러 상태를 화면에서 다룬다.
export default async function ExpressionDetailPage({
  params,
}: ExpressionDetailPageProps) {
  await requireAuthenticatedUserId();
  const { expression_id: expressionId } = await params;

  return <ExpressionDetailExperience expressionId={expressionId} />;
}

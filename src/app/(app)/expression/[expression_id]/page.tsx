import type { Metadata } from "next";

import { ExpressionDetailExperience } from "./ExpressionDetailExperience";

export const metadata: Metadata = {
  title: "표현 상세",
};

type ExpressionDetailPageProps = {
  params: Promise<{
    expression_id: string;
  }>;
};

// PPT 충실도 패스: s09_detail.PNG. 데이터는 GET /expressions/{expression_id}
// 목(mockExpressionDetail). id는 라우트 마운트 확인용으로만 받는다.
export default async function ExpressionDetailPage({
  params,
}: ExpressionDetailPageProps) {
  await params;

  return <ExpressionDetailExperience />;
}

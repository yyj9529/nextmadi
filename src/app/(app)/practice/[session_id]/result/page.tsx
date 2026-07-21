import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { RoleplayResultExperience } from "./RoleplayResultExperience";

export const metadata: Metadata = {
  title: "연습 결과",
};

type RoleplayResultPageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

// S12b 롤플레이 결과. (#63 실데이터 연결)
// 결과는 클라이언트가 GET /api/practice/sessions/{id}로 조회하고, 미생성이면
// POST /api/practice/sessions/{id}/result로 1회 생성한다(RoleplayResultExperience).
export default async function RoleplayResultPage({
  params,
}: RoleplayResultPageProps) {
  const { session_id: sessionId } = await params;

  const session = await auth();
  if (!session?.user?.id) {
    redirect("/login");
  }

  return <RoleplayResultExperience sessionId={sessionId} />;
}

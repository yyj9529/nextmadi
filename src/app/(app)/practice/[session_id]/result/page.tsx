import type { Metadata } from "next";

import { RoleplayResultExperience } from "./RoleplayResultExperience";

export const metadata: Metadata = {
  title: "연습 결과",
};

type RoleplayResultPageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

// PPT 충실도 패스: s12b_roleplay.PNG. 결과는 GET /practice/sessions/{id} +
// POST /practice/sessions/{id}/result 목(mockPracticeResult).
export default async function RoleplayResultPage({
  params,
}: RoleplayResultPageProps) {
  await params;

  return <RoleplayResultExperience />;
}

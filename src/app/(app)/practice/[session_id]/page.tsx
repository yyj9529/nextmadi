import type { Metadata } from "next";

import { RoleplayExperience } from "./RoleplayExperience";

export const metadata: Metadata = {
  title: "롤플레이",
};

type RoleplayPageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

// PPT 충실도 패스: s12_roleplay.PNG. 세션은 POST /practice/sessions →
// GET /practice/sessions/{session_id} 목, 턴 진행은
// POST /practice/sessions/{session_id}/turns — 목 패스에서는 스크립트
// (mockTurnScript)를 순서대로 재생한다.
export default async function RoleplayPage({ params }: RoleplayPageProps) {
  await params;

  return <RoleplayExperience />;
}

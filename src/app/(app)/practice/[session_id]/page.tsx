import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { listCoaches } from "@/lib/coach/list-coaches";
import type { Coach } from "@/lib/mock-api";
import { RoleplayExperience } from "./RoleplayExperience";

export const metadata: Metadata = {
  title: "롤플레이",
};

type RoleplayPageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

// S12 롤플레이. (#61 실데이터 연결)
// 세션 상태는 클라이언트가 GET /api/practice/sessions/{id}로 복원한다. 코치 표시명/전환 목록은
// 참조 데이터라 서버에서 GET /coaches를 조회해 props로 넘긴다(welcome/coach와 동일 패턴).
// 세션 자체는 S09 "연습하기"가 POST /practice/sessions로 만들어 이 URL로 라우팅한다.
export default async function RoleplayPage({ params }: RoleplayPageProps) {
  const { session_id: sessionId } = await params;

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    redirect("/login");
  }

  // 코치 조회 실패는 화면을 막지 않는다 — 이름 매핑만 못 하므로 빈 목록으로 진행한다
  // (세션 로드/턴 진행은 정상 동작; 코치명은 fallback "코치").
  let coaches: Coach[];
  try {
    coaches = await listCoaches({ userId });
  } catch {
    coaches = [];
  }

  return <RoleplayExperience sessionId={sessionId} coaches={coaches} />;
}

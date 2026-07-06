import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { listCoaches } from "@/lib/coach/list-coaches";
import { CoachSelectExperience } from "./CoachSelectExperience";
import { CoachSelectUnavailable } from "./CoachSelectUnavailable";

export const metadata: Metadata = {
  title: "코치 선택",
};

// S03b 코치 선택 (신규 1회). 카드 데이터는 GET /coaches를 서버에서 조회해 넘긴다.
// 선택 완료는 CoachSelectExperience가 PATCH /me { selected_coach_id, is_onboarded: true }
// 로 저장한 뒤 /home(또는 대기 중 저장 흐름)으로 라우팅한다.
export default async function CoachSelectPage() {
  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    redirect("/login");
  }

  let coaches;
  try {
    coaches = await listCoaches({ userId });
  } catch {
    // 조회 실패 — 재시도를 안내한다(카드 없이 온보딩을 진행시킬 수 없음).
    return <CoachSelectUnavailable />;
  }

  // 코치가 시드되지 않았으면 선택 자체가 불가능하다(s03b.md edge case).
  if (coaches.length === 0) {
    return <CoachSelectUnavailable />;
  }

  const displayName = session?.user?.name ?? "";
  return <CoachSelectExperience coaches={coaches} displayName={displayName} />;
}

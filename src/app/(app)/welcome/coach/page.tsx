import type { Metadata } from "next";

import { CoachSelectExperience } from "./CoachSelectExperience";

export const metadata: Metadata = {
  title: "코치 선택",
};

// PPT 충실도 패스: s03b_coach.PNG. 카드 데이터는 GET /coaches 목.
// 선택 완료는 PATCH /me { selected_coach_id, is_onboarded: true } 후
// /home 라우팅 — 목 패스에서는 바로 /home으로 이동한다.
export default function CoachSelectPage() {
  return <CoachSelectExperience />;
}

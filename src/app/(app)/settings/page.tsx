import type { Metadata } from "next";

import { SettingsExperience } from "./SettingsExperience";

export const metadata: Metadata = {
  title: "설정",
};

// PPT 충실도 패스: s11_settings.PNG. 프로필/사용량은 GET /me +
// GET /usage/today 목. 닉네임·코치 변경은 PATCH /me, 계정 삭제는
// DELETE /me — 목 패스에서는 로컬 상태/라우팅으로 시뮬레이션한다.
export default function SettingsPage() {
  return <SettingsExperience />;
}

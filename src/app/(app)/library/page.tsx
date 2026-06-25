import type { Metadata } from "next";

import { BottomNav } from "@/components/app/BottomNav";
import { requireAuthenticatedUserId } from "@/lib/auth/require-authenticated-user";
import { LibraryExperience } from "./LibraryExperience";

export const metadata: Metadata = {
  title: "내 기록",
};

// S08 내 기록. 인증된 사용자만 진입하며, 목록/검색은 BFF GET /api/expressions로 조회한다.
export default async function LibraryPage() {
  await requireAuthenticatedUserId();

  return (
    <div className="app-screen library-screen has-bottom-nav">
      <LibraryExperience />
      <BottomNav active="library" />
    </div>
  );
}

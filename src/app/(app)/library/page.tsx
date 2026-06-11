import type { Metadata } from "next";

import { BottomNav } from "@/components/app/BottomNav";
import { LibraryExperience } from "./LibraryExperience";

export const metadata: Metadata = {
  title: "내 기록",
};

// PPT 충실도 패스: s08_library.PNG. 목록은 GET /expressions?limit=20,
// 검색은 GET /expressions?q={keyword} — 목 패스에서는 클라이언트에서
// mockLibraryItems를 필터링한다.
export default function LibraryPage() {
  return (
    <div className="app-screen library-screen has-bottom-nav">
      <LibraryExperience />
      <BottomNav active="library" />
    </div>
  );
}

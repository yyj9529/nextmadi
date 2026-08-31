import type { Metadata } from "next";

import { requireAuthenticatedUserId } from "@/lib/auth/require-authenticated-user";
import { listCoaches } from "@/lib/coach/list-coaches";
import { getUsageToday } from "@/lib/usage/get-usage-today";
import { getMe } from "@/lib/user/get-me";
import { SettingsExperience } from "./SettingsExperience";
import { SettingsUnavailable } from "./SettingsUnavailable";

export const metadata: Metadata = {
  title: "설정",
};

// S11 설정 (#56). 첫 페인트 데이터는 서버에서 GET /me + GET /usage/today +
// GET /coaches를 병렬 조회해 넘긴다. 변경(닉네임·코치)은 클라이언트가 PATCH /api/me로
// 한다. 계정 삭제(DELETE /me)는 백엔드 미구현이라 이 티켓 범위 밖 — 확인 다이얼로그까지만
// 동작하고 실제 삭제는 E03.7 티켓에서 배선한다.
export default async function SettingsPage() {
  const userId = await requireAuthenticatedUserId();

  let me;
  let usage;
  let coaches;
  try {
    [me, usage, coaches] = await Promise.all([
      getMe({ userId }),
      getUsageToday({ userId }),
      listCoaches({ userId }),
    ]);
  } catch (error) {
    // 삼키지 않고 서버 로그에 남긴다. 화면은 "불러오지 못했어요" 하나뿐이라
    // 백엔드 다운인지 401인지 5xx인지 로그 없이는 구분할 수 없다.
    console.error("[settings] initial load failed", error);
    // 프로필 없이 설정 화면을 의미 있게 그릴 수 없다 — 재시도를 안내한다.
    return <SettingsUnavailable />;
  }

  // 가입일은 뷰어 로컬 시간대로 떨어뜨려야 해서 서버에서 포맷하지 않는다(#56).
  // created_at(ISO)을 그대로 넘기고 클라이언트가 마운트 후 라벨을 만든다.
  return <SettingsExperience me={me} usage={usage} coaches={coaches} />;
}

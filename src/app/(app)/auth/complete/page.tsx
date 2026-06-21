import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { PostLoginRedirect } from "./PostLoginRedirect";

export default async function AuthCompletePage() {
  const session = await auth();

  if (!session?.user?.id) {
    redirect("/login?callback_error=oauth");
  }

  // 온보딩 전이면 코치 선택(S03b)이 우선한다 — 서버에서 바로 리다이렉트.
  if (!session.user.isOnboarded) {
    redirect("/welcome/coach");
  }

  // 온보딩된 사용자는 pending_save(클라이언트 전용)에 따라 S07 또는 /home으로 분기한다.
  return <PostLoginRedirect />;
}

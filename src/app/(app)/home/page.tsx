import type { Metadata } from "next";

import { requireAuthenticatedUserId } from "@/lib/auth/require-authenticated-user";
import { HomeDashboard } from "./HomeDashboard";

export const metadata: Metadata = {
  title: "홈",
};

// S04 홈. 인증된 사용자만 진입한다. 첫 페인트 집계는 BFF GET /api/home/dashboard
// (Spring Boot GET /home/dashboard 프록시)로 한 번에 불러온다. (#55)
export default async function HomePage() {
  await requireAuthenticatedUserId();

  return <HomeDashboard />;
}

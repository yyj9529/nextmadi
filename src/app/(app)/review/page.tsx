import type { Metadata } from "next";

import { requireAuthenticatedUserId } from "@/lib/auth/require-authenticated-user";
import { ReviewExperience } from "./ReviewExperience";

export const metadata: Metadata = {
  title: "복습",
};

// S10 복습. 인증된 사용자만 진입한다. 큐는 BFF GET /api/review/today,
// 평가 제출은 POST /api/review/{review_card_id}/submit 으로 서버에 즉시 반영된다.
export default async function ReviewPage() {
  await requireAuthenticatedUserId();

  return <ReviewExperience />;
}

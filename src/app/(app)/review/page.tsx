import type { Metadata } from "next";

import { ReviewExperience } from "./ReviewExperience";

export const metadata: Metadata = {
  title: "복습",
};

// PPT 충실도 패스: s10_review.PNG. 큐는 GET /review/today?limit=10 목,
// 평가 제출은 POST /review/{review_card_id}/submit — 목 패스에서는
// 로컬 진행으로 시뮬레이션한다.
export default function ReviewPage() {
  return <ReviewExperience />;
}

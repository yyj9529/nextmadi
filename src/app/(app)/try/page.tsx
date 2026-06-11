import type { Metadata } from "next";

import { mockLandingExamples } from "@/lib/mock-api";
import { TryExperience } from "./TryExperience";

export const metadata: Metadata = {
  title: "1분 체험",
};

type TryPageProps = {
  searchParams: Promise<{ example?: string }>;
};

// PPT 충실도 패스: s02_try.PNG. S01 예시 카드 클릭 시 ?example={id}로
// 진입해 입력 필드를 자동 채움(스펙 s01/s02).
export default async function TryPage({ searchParams }: TryPageProps) {
  const { example } = await searchParams;
  const prefill =
    mockLandingExamples.find((item) => item.id === example)?.korean_text ?? "";

  return <TryExperience initialText={prefill} />;
}

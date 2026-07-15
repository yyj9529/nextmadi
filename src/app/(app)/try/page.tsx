import type { Metadata } from "next";

import { TryExperience } from "./TryExperience";

export const metadata: Metadata = {
  title: "1분 체험",
};

// TryExperience의 textarea 입력 한도와 동일해야 한다(현재 500). 여긴 서버 컴포넌트라
// "use client" 모듈의 export를 import하면 값이 아니라 client 참조가 되므로 여기서 직접 둔다.
const MAX_PREFILL_LENGTH = 500;

type TryPageProps = {
  searchParams: Promise<{ text?: string }>;
};

// S01 예시 카드 클릭 시 ?text={korean_text}로 진입해 입력 필드를 자동 채움(스펙 s01/s02).
// 예시 목록(GET /landing/examples)은 매 방문 무작위 3개라 id로 재조회할 수 없으므로,
// 카드가 문장 자체를 넘긴다. 입력 한도(500자)를 넘는 값은 잘라 textarea 계약을 지킨다.
export default async function TryPage({ searchParams }: TryPageProps) {
  const { text } = await searchParams;
  const prefill = (text ?? "").slice(0, MAX_PREFILL_LENGTH);

  return <TryExperience initialText={prefill} />;
}

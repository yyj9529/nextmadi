// 가입일 표시 라벨. (#56, S11 프로필)
//
// users.created_at은 TIMESTAMPTZ(시점)이고 백엔드는 offset이 붙은 ISO 문자열을 준다.
// 어느 시간대로 떨어뜨리느냐에 따라 하루가 밀리므로, 표시는 항상 **뷰어의 로컬 시간대**
// 기준이다 — 사용자가 체감하는 날짜와 일치해야 하고, 타겟 유저가 미국 거주라
// 서버(프로덕션 UTC) 기준으로 찍으면 실제로 어긋난다.
//
// 로컬 시간대에 의존하므로 이 함수는 클라이언트에서만 호출한다(SSR 시 서버 TZ로 계산되어
// hydration 불일치가 난다). 호출 측은 마운트 이후에 렌더한다.
// 기존 관례와 같다: relative-time.ts도 로컬 getter를 쓴다.

export function formatJoinedLabel(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}.${month}.${day}`;
}

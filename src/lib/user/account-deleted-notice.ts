// 계정 삭제 예약 후 S01에 띄우는 안내. (#24, S11 User Story 3 AC2)
//
// 설정 화면은 로그아웃하면서 사라지므로 안내를 state로 들고 갈 수 없다. 도착 URL의
// 쿼리 파라미터로 넘기고 랜딩이 그것만 보고 렌더한다 — 새로고침하면 사라지는 일회성 안내다.
//
// 파라미터 이름과 문구를 한 곳에 두는 이유는 보내는 쪽(설정)과 읽는 쪽(랜딩)이 서로 다른
// 렌더 환경이기 때문이다. 한쪽만 바뀌면 안내가 조용히 안 뜬다.

export const ACCOUNT_DELETED_PARAM = "account_deleted";

export const ACCOUNT_DELETED_MESSAGE = "14일 안에 다시 로그인하면 복원돼요";

/** 랜딩이 삭제 안내를 띄워야 하는가. 값은 보지 않고 존재만 본다. */
export function shouldShowAccountDeletedNotice(
  searchParams: Record<string, string | string[] | undefined> | undefined,
): boolean {
  return searchParams?.[ACCOUNT_DELETED_PARAM] !== undefined;
}

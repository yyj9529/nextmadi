// 가입 전(S02) 익명 분석 세션 토큰을 담는 httpOnly 쿠키 이름. (#42)
//
// 익명 분석 BFF 플로우(S02 → POST /analysis, 별도 티켓에서 구현)가 이 쿠키를 설정하고,
// pending-save claim 시 BFF 저장 라우트가 "서버에서" 읽어 Spring으로 전달한다. 브라우저 JS가
// 읽지 못하도록 httpOnly여야 하며, 그래서 pending_save sessionStorage에는 담기지 않는다.
export const ANON_SESSION_COOKIE = "phraselog_anon_session";

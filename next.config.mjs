/** @type {import('next').NextConfig} */
const nextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,
  // dev 인디케이터가 하단 네비를 가려 스크린샷 비교를 방해해서 비활성화
  devIndicators: false,
  // dev 서버는 Origin 이 허용 목록에 없으면 HMR 웹소켓 업그레이드를 거절하는데,
  // 기본 허용 목록에 localhost 는 있고 127.0.0.1 은 없다. 127.0.0.1 로 열면
  // 핸드셰이크가 깨지고(Chrome: ERR_INVALID_HTTP_RESPONSE) HMR 이 못 붙는데,
  // Turbopack dev 클라이언트는 그 연결 위에서 하이드레이션되므로 화면이 SSR
  // 상태로 굳는다 — 입력해도 state 가 안 바뀐다(#131). 두 주소 모두 쓰도록 허용한다.
  // dev 전용 설정이라 프로덕션 빌드에는 영향이 없다.
  allowedDevOrigins: ["127.0.0.1"],
};

export default nextConfig;

/** @type {import('next').NextConfig} */
const nextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,
  // dev 인디케이터가 하단 네비를 가려 스크린샷 비교를 방해해서 비활성화
  devIndicators: false,
};

export default nextConfig;

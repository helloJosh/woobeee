/** @type {import('next').NextConfig} */
const MVC_ORIGIN = process.env.MVC_ORIGIN || "http://localhost:8000"
const WEBFLUX_ORIGIN = process.env.WEBFLUX_ORIGIN || "http://localhost:8001"
// 블로그 본문 이미지는 MinIO 에 있다. 브라우저가 MinIO(:9000)에 직접 붙게 두면 공개
// 도메인에서 열리지 않으므로(그 포트는 외부에 노출돼 있지 않다) 여기서 프록시한다.
const S3_ORIGIN = process.env.S3_ORIGIN || "http://localhost:9000"
const S3_BUCKET = process.env.S3_BUCKET || "woobeee"

const nextConfig = {
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  // 주의: rewrites 는 HTTP 만 프록시한다. WebSocket(/ws/game)은 여기로 오지 않고
  // NEXT_PUBLIC_WS_BASE_URL 로 브라우저가 직접 붙는다. lib/game-config.ts 참고.
  async rewrites() {
    return [
      // blog + auth surface -> app-mvc (Tomcat/JPA)
      { source: "/api/auth/:path*", destination: `${MVC_ORIGIN}/api/auth/:path*` },
      { source: "/api/back/:path*", destination: `${MVC_ORIGIN}/api/back/:path*` },
      // game surface -> app-webflux (Netty/R2DBC)
      { source: "/api/game/:path*", destination: `${WEBFLUX_ORIGIN}/api/game/:path*` },
      // 글 본문 이미지 -> MinIO. app-mvc 가 본문에 박는 주소(S3_PUBLIC_BASE_URL)의 경로부와
      // 맞춰야 한다: {public-base-url}/{bucket}/{postId}/{파일명}.
      { source: `/${S3_BUCKET}/:path*`, destination: `${S3_ORIGIN}/${S3_BUCKET}/:path*` },
    ]
  },
}

export default nextConfig

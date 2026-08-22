/** @type {import('next').NextConfig} */
const MVC_ORIGIN = process.env.MVC_ORIGIN || "http://localhost:8000"
const WEBFLUX_ORIGIN = process.env.WEBFLUX_ORIGIN || "http://localhost:8001"

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
    ]
  },
}

export default nextConfig

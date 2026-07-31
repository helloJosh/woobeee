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

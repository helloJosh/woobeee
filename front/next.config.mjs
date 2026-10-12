/** @type {import('next').NextConfig} */
const MVC_ORIGIN = process.env.MVC_ORIGIN || "http://localhost:8000"

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
      // blog + auth + schedule -> app-mvc (Tomcat/JPA)
      { source: "/api/auth/:path*", destination: `${MVC_ORIGIN}/api/auth/:path*` },
      { source: "/api/back/:path*", destination: `${MVC_ORIGIN}/api/back/:path*` },
    ]
  },
}

export default nextConfig

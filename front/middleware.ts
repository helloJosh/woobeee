import { NextResponse, type NextRequest } from "next/server"
import { blogRedirectTarget } from "@/lib/blog-redirect"

/**
 * /blog 는 홈(/)으로 올라갔다. 페이지 안의 redirect() 는 레이아웃이 스트리밍을 시작한 뒤라 meta refresh 로
 * 떨어져 curl·크롤러가 200 을 본다 — 여기서 진짜 307 을 내고 category·search·tag 쿼리만 옮긴다.
 */
export function middleware(request: NextRequest) {
    const target = blogRedirectTarget(request.nextUrl.searchParams)
    return NextResponse.redirect(new URL(target, request.url), 307)
}

export const config = {
    matcher: ["/blog"],
}

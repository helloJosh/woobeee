import { NextResponse, type NextRequest } from "next/server"
import { blogFilterTarget } from "@/lib/blog-redirect"

/**
 * 홈(/)은 공동구매이고 블로그는 /blog 다. 한때 블로그가 / 에 있었으므로 `/?category=3` 같은
 * 옛 링크·북마크가 돌아다닌다 — 그런 요청만 /blog 로 넘긴다. 필터가 없는 그냥 / 는 홈이다.
 *
 * 페이지 안의 redirect() 는 레이아웃이 스트리밍을 시작한 뒤라 meta refresh 로 떨어져
 * curl·크롤러가 200 을 본다. 여기서 진짜 307 을 낸다.
 */
export function middleware(request: NextRequest) {
    const target = blogFilterTarget(request.nextUrl.searchParams)
    if (target === null) {
        return NextResponse.next()
    }
    return NextResponse.redirect(new URL(target, request.url), 307)
}

export const config = {
    matcher: ["/"],
}

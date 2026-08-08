/**
 * WebSocket 은 Next.js rewrites 를 타지 않는다 — rewrites 는 HTTP 만 프록시한다.
 * 그래서 소켓만 WebFlux 오리진으로 직접 붙는다.
 */
const DEFAULT_WS_BASE_URL = "ws://localhost:8001"

export function gameSocketUrl(): string {
    const configured = process.env.NEXT_PUBLIC_WS_BASE_URL?.trim()
    if (configured) {
        return `${configured.replace(/\/$/, "")}/ws/game`
    }

    // 같은 오리진으로 폴백하면 소켓이 Next 로 가고, Next 는 업그레이드를 못 하므로
    // 반드시 실패한다. 그래서 브라우저에서도 개발 기본값을 쓰고, 대신 이유를 남긴다.
    if (typeof window !== "undefined") {
        console.warn(
            `NEXT_PUBLIC_WS_BASE_URL 미설정 — 개발 기본값 ${DEFAULT_WS_BASE_URL} 을 쓴다. ` +
                "배포 환경이라면 WebFlux 오리진을 지정해야 한다."
        )
    }

    return `${DEFAULT_WS_BASE_URL}/ws/game`
}

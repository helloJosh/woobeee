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

    if (typeof window !== "undefined") {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:"
        return `${protocol}//${window.location.host}/ws/game`
    }

    return `${DEFAULT_WS_BASE_URL}/ws/game`
}

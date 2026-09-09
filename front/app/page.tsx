import { Suspense } from "react"
import HomePage from "@/components/home/home-page"

// 홈은 기술블로그다. 게임 허브는 /game 으로 내려갔다(스펙 2026-09-09).
// HomePage 가 useSearchParams 를 읽으므로 Suspense 경계가 필요하다.
export default function RootPage() {
    return (
        <Suspense fallback={null}>
            <HomePage />
        </Suspense>
    )
}

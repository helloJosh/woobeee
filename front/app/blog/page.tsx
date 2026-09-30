import { Suspense } from "react"
import HomePage from "@/components/home/home-page"

// 기술블로그. 홈(/)이 공동구매가 되면서 여기로 내려왔다.
// HomePage 가 useSearchParams 를 읽으므로 Suspense 경계가 필요하다.
export default function BlogPage() {
    return (
        <Suspense fallback={null}>
            <HomePage />
        </Suspense>
    )
}

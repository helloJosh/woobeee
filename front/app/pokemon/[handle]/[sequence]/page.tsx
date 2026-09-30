"use client"

import { useEffect } from "react"
import { useParams, useRouter } from "next/navigation"
import { Skeleton } from "@/components/ui/skeleton"

/**
 * {@code /pokemon/{handle}/{n}} — 공유하기 좋은 주소를 유지하되, 화면은 주최자 방의 탭
 * 하나로 보여준다. 차수 본문을 두 곳에서 그리면 언젠가 어긋나므로 한 곳으로 모은다.
 */
export default function PokemonRoundRedirectPage() {
    const params = useParams<{ handle: string; sequence: string }>()
    const router = useRouter()
    const handle = params?.handle ?? ""
    const sequence = params?.sequence ?? ""

    useEffect(() => {
        if (handle === "") {
            return
        }
        router.replace(`/pokemon/${handle}?round=${encodeURIComponent(sequence)}`)
    }, [handle, sequence, router])

    return (
        <main className="mx-auto max-w-4xl space-y-4 px-4 py-8">
            <Skeleton className="h-10 w-48" />
            <Skeleton className="h-64 w-full" />
        </main>
    )
}

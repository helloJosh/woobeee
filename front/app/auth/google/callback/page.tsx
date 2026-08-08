"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { buildAuthHref, consumePendingRedirect } from "@/lib/auth-redirect"
import { useAuth } from "@/hooks/use-auth"

export default function GoogleCallbackPage() {
    const router = useRouter()
    const searchParams = useSearchParams()
    const { completeGoogleAuthorization } = useAuth()
    const handledRef = useRef(false)
    const [errorMessage, setErrorMessage] = useState<string | null>(null)
    // 실패했을 때 "로그인으로 돌아가기" 가 데려갈 곳. 목적지는 아래 이펙트가 딱 한 번
    // 꺼내 가므로(그 뒤 sessionStorage 에는 없다), 다시 쓰려면 여기에 들고 있어야 한다.
    // 이것이 없으면 Google 인증에 실패한 초대 손님은 재시도하는 순간 초대를 잃는다 —
    // 이 화면이 이 태스크가 막으려는 바로 그 구멍의 마지막 하나다.
    const [retryNext, setRetryNext] = useState<string | null>(null)

    useEffect(() => {
        if (handledRef.current) {
            return
        }

        const code = searchParams.get("code")
        const state = searchParams.get("state")
        const error = searchParams.get("error")
        handledRef.current = true

        // 어떤 갈래로 빠지든 그 전에 꺼낸다. 이 왕복이 남긴 항목은 성공이든, 사용자가 동의를
        // 취소했든, 응답이 망가졌든 여기서 정확히 한 번 사라져야 한다. (state 가 없으면
        // consumePendingRedirect 는 아무것도 건드리지 않고 "/" 를 돌려준다.)
        const next = consumePendingRedirect(state)
        setRetryNext(next)

        if (error) {
            setErrorMessage("Google 인증이 취소되었거나 실패했습니다.")
            return
        }

        if (!code || !state) {
            setErrorMessage("Google 인증 응답이 올바르지 않습니다.")
            return
        }

        completeGoogleAuthorization(code, state)
            .then(() => router.replace(next))
            .catch(() => setErrorMessage("로그인 처리에 실패했습니다. 다시 시도해주세요."))
    }, [completeGoogleAuthorization, router, searchParams])

    if (errorMessage) {
        return (
            <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-4 text-center">
                <p className="text-sm text-muted-foreground">{errorMessage}</p>
                <Button
                    type="button"
                    onClick={() => router.replace(buildAuthHref("/login", retryNext))}
                >
                    로그인으로 돌아가기
                </Button>
            </main>
        )
    }

    return (
        <main className="flex min-h-screen items-center justify-center gap-3 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Google 인증 처리 중...
        </main>
    )
}

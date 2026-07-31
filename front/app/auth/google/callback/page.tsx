"use client"

import { useEffect, useRef, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/hooks/use-auth"

export default function GoogleCallbackPage() {
    const router = useRouter()
    const searchParams = useSearchParams()
    const { completeGoogleAuthorization } = useAuth()
    const handledRef = useRef(false)
    const [errorMessage, setErrorMessage] = useState<string | null>(null)

    useEffect(() => {
        if (handledRef.current) {
            return
        }

        const code = searchParams.get("code")
        const state = searchParams.get("state")
        const error = searchParams.get("error")
        handledRef.current = true

        if (error) {
            setErrorMessage("Google 인증이 취소되었거나 실패했습니다.")
            return
        }

        if (!code || !state) {
            setErrorMessage("Google 인증 응답이 올바르지 않습니다.")
            return
        }

        completeGoogleAuthorization(code, state)
            .then(() => router.replace("/"))
            .catch(() => setErrorMessage("로그인 처리에 실패했습니다. 다시 시도해주세요."))
    }, [completeGoogleAuthorization, router, searchParams])

    if (errorMessage) {
        return (
            <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-4 text-center">
                <p className="text-sm text-muted-foreground">{errorMessage}</p>
                <Button type="button" onClick={() => router.replace("/login")}>
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

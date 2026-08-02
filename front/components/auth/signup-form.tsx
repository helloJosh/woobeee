"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowLeft } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import GoogleAuthButton from "@/components/auth/google-auth-button"
import { NEXT_PARAM, buildAuthHref, sanitizeNextPath } from "@/lib/auth-redirect"
import { useAuth } from "@/hooks/use-auth"

/** 이 컴포넌트는 useSearchParams 를 쓴다 — 호출부가 Suspense 경계로 감싸야 한다. */
export default function SignupForm() {
    const { user, loading, isAuthenticated } = useAuth()
    const router = useRouter()
    const [nickname, setNickname] = useState("")
    // 초대 링크 -> 게이트 -> 로그인 -> "계정이 없으신가요?" 로 흘러온 방문자의 목적지.
    // 로그인 화면과 같은 규칙으로 읽고 거른다.
    const searchParams = useSearchParams()
    const next = sanitizeNextPath(searchParams.get(NEXT_PARAM))

    const goBack = () => {
        if (window.history.length > 1) {
            router.back()
            return
        }

        router.push("/")
    }

    useEffect(() => {
        if ((user || isAuthenticated) && !loading) {
            router.push(next)
        }
    }, [user, isAuthenticated, loading, router, next])

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
            </div>
        )
    }

    if (user || isAuthenticated) {
        return null
    }

    return (
        <div className="min-h-screen flex items-center justify-center bg-background p-4">
            <div className="w-full max-w-md space-y-6">
                <Button variant="ghost" type="button" className="self-start" onClick={goBack}>
                    <ArrowLeft className="h-4 w-4" />
                    이전화면으로 돌아가기
                </Button>

                <div className="space-y-2">
                    <h1 className="text-2xl font-semibold">회원가입</h1>
                    <p className="text-sm text-muted-foreground">
                        Google 인증으로 계정을 생성합니다.
                    </p>
                </div>

                <Input
                    value={nickname}
                    onChange={(event) => setNickname(event.target.value)}
                    placeholder="닉네임"
                    maxLength={60}
                />

                <GoogleAuthButton mode="signin" nickname={nickname} className="w-full" next={next} />

                <div className="text-center text-sm text-muted-foreground">
                    이미 계정이 있으신가요?{" "}
                    <Link href={buildAuthHref("/login", next)} className="text-primary hover:underline">
                        로그인
                    </Link>
                </div>

                <div className="text-center text-xs text-muted-foreground">
                    회원가입하면{" "}
                    <Link href="#" className="hover:underline">
                        서비스 약관
                    </Link>
                    과{" "}
                    <Link href="#" className="hover:underline">
                        개인정보 처리방침
                    </Link>
                    에 동의하는 것으로 간주됩니다.
                </div>
            </div>
        </div>
    )
}

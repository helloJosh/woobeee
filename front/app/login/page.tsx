"use client"

import { Suspense, useEffect } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { ArrowLeft } from "lucide-react"
import GoogleAuthButton from "@/components/auth/google-auth-button"
import { NEXT_PARAM, buildAuthHref, sanitizeNextPath } from "@/lib/auth-redirect"
import { useAuth } from "@/hooks/use-auth"

/**
 * useSearchParams 를 쓰는 클라이언트 컴포넌트는 Suspense 경계 안에 있어야 한다 — 없으면
 * 정적 프리렌더 단계에서 next build 가 이 페이지에서 멈춘다.
 */
export default function LoginPage() {
    return (
        <Suspense fallback={<LoginFallback />}>
            <LoginScreen />
        </Suspense>
    )
}

function LoginScreen() {
    const { user, loading, isAuthenticated } = useAuth()
    const router = useRouter()
    // 초대 링크를 열었다가 여기로 온 방문자는 `?next=/game/omok/<id>?invite=<code>` 를 달고
    // 온다. 그 방으로 돌려보내는 것이 이 화면의 절반이다. 값은 사용자가 고칠 수 있으므로
    // 읽는 즉시 sanitize 한다 — 그렇지 않으면 오픈 리다이렉트다.
    const searchParams = useSearchParams()
    const next = sanitizeNextPath(searchParams.get(NEXT_PARAM))

    useEffect(() => {
        if ((user || isAuthenticated) && !loading) {
            router.push(next)
        }
    }, [user, isAuthenticated, loading, router, next])

    if (loading) {
        return <LoginFallback />
    }

    if (user || isAuthenticated) {
        return null // 리다이렉트 중
    }

    return (
        <div className="min-h-screen flex items-center justify-center bg-background p-4">
            <div className="w-full max-w-md space-y-6">
                {/* 뒤로가기 버튼 */}
                <Button variant="ghost" asChild className="self-start">
                    <Link href="/" className="flex items-center gap-2">
                        <ArrowLeft className="h-4 w-4" />
                        홈으로 돌아가기
                    </Link>
                </Button>

                {/* 이메일 로그인 폼 */}
                {/*<LoginForm />*/}
                {/*<div className="relative">*/}
                {/*    <div className="absolute inset-0 flex items-center">*/}
                {/*        <Separator className="w-full" />*/}
                {/*    </div>*/}
                {/*    <div className="relative flex justify-center text-xs uppercase">*/}
                {/*        <span className="bg-background px-2 text-muted-foreground">또는</span>*/}
                {/*    </div>*/}
                {/*</div>*/}

                {/* Google 로그인 */}

                <GoogleAuthButton mode="login" className="w-full" next={next} />
                <div className="text-center text-sm text-muted-foreground">
                    계정이 없으신가요?{" "}
                    {/* 계정이 없어 회원가입으로 새는 방문자도 목적지를 잃지 않게 함께 넘긴다. */}
                    <Link href={buildAuthHref("/signup", next)} className="text-primary hover:underline">
                        회원가입
                    </Link>
                </div>

                {/*<div className="text-center text-xs text-muted-foreground">*/}
                {/*    로그인하면{" "}*/}
                {/*    <Link href="#" className="hover:underline">*/}
                {/*        서비스 약관*/}
                {/*    </Link>*/}
                {/*    과{" "}*/}
                {/*    <Link href="#" className="hover:underline">*/}
                {/*        개인정보 처리방침*/}
                {/*    </Link>*/}
                {/*    에 동의하는 것으로 간주됩니다.*/}
                {/*</div>*/}
            </div>
        </div>
    )
}

function LoginFallback() {
    return (
        <div className="min-h-screen flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
        </div>
    )
}

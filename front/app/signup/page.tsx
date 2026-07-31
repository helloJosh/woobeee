"use client"

import { useEffect } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ArrowLeft, ShoppingBag, Store } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/hooks/use-auth"

export default function SignupPage() {
    const { user, loading, isAuthenticated } = useAuth()
    const router = useRouter()

    const goBack = () => {
        if (window.history.length > 1) {
            router.back()
            return
        }

        router.push("/")
    }

    useEffect(() => {
        if ((user || isAuthenticated) && !loading) {
            router.push("/")
        }
    }, [user, isAuthenticated, loading, router])

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
                        계정 유형을 선택해주세요.
                    </p>
                </div>

                <div className="grid gap-3">
                    <Button asChild variant="outline" className="h-14 justify-start gap-3 px-4">
                        <Link href="/signup/buyer">
                            <ShoppingBag className="h-5 w-5" />
                            구매자 회원가입
                        </Link>
                    </Button>
                    <Button asChild variant="outline" className="h-14 justify-start gap-3 px-4">
                        <Link href="/signup/seller">
                            <Store className="h-5 w-5" />
                            판매자 회원가입
                        </Link>
                    </Button>
                </div>

                <div className="text-center text-sm text-muted-foreground">
                    이미 계정이 있으신가요?{" "}
                    <Link href="/login" className="text-primary hover:underline">
                        로그인
                    </Link>
                </div>
            </div>
        </div>
    )
}

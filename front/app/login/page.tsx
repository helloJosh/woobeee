"use client"

import { useEffect } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { ArrowLeft } from "lucide-react"
import GoogleAuthButton from "@/components/auth/google-auth-button"
import { useAuth } from "@/hooks/use-auth"
import { useState } from "react"

export default function LoginPage() {
    const { user, loading, isAuthenticated } = useAuth()
    const router = useRouter()
    const [memberType, setMemberType] = useState<"BUYER" | "SELLER">("BUYER")

    useEffect(() => {
        if ((user || isAuthenticated) && !loading) {
            router.push("/")
        }
    }, [user, isAuthenticated, loading, router])

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
            </div>
        )
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

                <div className="grid grid-cols-2 gap-2">
                    <Button
                        type="button"
                        variant={memberType === "BUYER" ? "default" : "outline"}
                        onClick={() => setMemberType("BUYER")}
                    >
                        구매자
                    </Button>
                    <Button
                        type="button"
                        variant={memberType === "SELLER" ? "default" : "outline"}
                        onClick={() => setMemberType("SELLER")}
                    >
                        판매자
                    </Button>
                </div>

                <GoogleAuthButton mode="login" memberType={memberType} className="w-full" />
                <div className="text-center text-sm text-muted-foreground">
                    계정이 없으신가요?{" "}
                    <Link href="/signup" className="text-primary hover:underline">
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

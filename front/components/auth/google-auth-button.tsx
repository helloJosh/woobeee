"use client"

import { useState } from "react"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/hooks/use-auth"

interface GoogleAuthButtonProps {
    mode: "signin" | "login"
    nickname?: string
    className?: string
}

export default function GoogleAuthButton({
    mode,
    nickname = "Google 사용자",
    className,
}: GoogleAuthButtonProps) {
    const [loading, setLoading] = useState(false)
    const { startGoogleLogin, startGoogleSignup } = useAuth()

    const handleClick = async () => {
        setLoading(true)
        try {
            if (mode === "signin") {
                await startGoogleSignup(nickname.trim() || "Google 사용자")
            } else {
                await startGoogleLogin()
            }
        } catch (error) {
            console.error("Google OAuth start failed:", error)
            alert("Google 인증을 시작하지 못했습니다. 잠시 후 다시 시도해주세요.")
            setLoading(false)
        }
    }

    return (
        <Button
            type="button"
            variant="outline"
            className={className}
            onClick={handleClick}
            disabled={loading}
        >
            {loading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
            {mode === "login" ? "Google로 로그인" : "Google로 회원가입"}
        </Button>
    )
}

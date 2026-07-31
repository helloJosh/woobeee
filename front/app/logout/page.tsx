"use client"

import { useEffect } from "react"
import { useRouter } from "next/navigation"
import { useAuth } from "@/hooks/use-auth" // 전역 상태가 있다면

export default function LogoutPage() {
    const router = useRouter()
    const { logout } = useAuth()

    useEffect(() => {
        try {
            logout()
        } finally {
            router.replace("/") // 또는 "/login"
        }
    }, [logout, router])

    return (
        <main className="flex h-[60vh] items-center justify-center text-sm text-muted-foreground">
            로그아웃 중...
        </main>
    )
}

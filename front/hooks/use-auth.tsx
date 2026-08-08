"use client"

import { useState, useEffect, createContext, useContext } from "react"
import type { ReactNode } from "react"
import { authAPI, tokenManager } from "@/lib/api"
import { rememberPendingRedirect } from "@/lib/auth-redirect"
import type { User } from "@/lib/types"

interface AuthContextType {
    user: User | null
    loading: boolean
    /**
     * next: 인증을 마치고 돌아갈 앱 내부 경로. Google 은 사이트를 완전히 떠났다 돌아오므로
     * 쿼리 파라미터로는 나를 수 없다 — 여기서 서버가 준 OAuth state 에 묶어 둔다.
     * lib/auth-redirect.ts 참고.
     */
    startGoogleLogin: (next?: string | null) => Promise<void>
    startGoogleSignup: (nickname: string, next?: string | null) => Promise<void>
    completeGoogleAuthorization: (code: string, state: string) => Promise<void>
    logout: () => Promise<void>
    isAuthenticated: boolean
    memberId: number | null
    role: string | null
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export function useAuth() {
    const context = useContext(AuthContext)
    if (context === undefined) {
        throw new Error("useAuth must be used within an AuthProvider")
    }
    return context
}

export function AuthProvider({ children }: { children: ReactNode }) {
    const [user, setUser] = useState<User | null>(null)
    const [loading, setLoading] = useState(true)
    const [isAuthenticated, setIsAuthenticated] = useState(false)
    const [memberId, setMemberId] = useState<number | null>(null)
    const [role, setRole] = useState<string | null>(null)

    useEffect(() => {
        const initAuth = async () => {
            const token = tokenManager.getToken()
            setIsAuthenticated(!!token)
            setMemberId(tokenManager.getMemberId())
            setRole(tokenManager.getRole())
            setLoading(false)
        }

        initAuth()
    }, [])

    // rememberPendingRedirect 는 반드시 assign 앞이다. assign 뒤에 두면 페이지가 이미
    // 떠나는 중이라 실행이 보장되지 않는다.
    const startGoogleLogin = async (next?: string | null) => {
        try {
            const response = await authAPI.startGoogleLogin()
            rememberPendingRedirect(response.state, next)
            window.location.assign(response.authorizationUrl)
        } catch (error) {
            console.error("Google login start failed:", error)
            throw error
        }
    }

    const startGoogleSignup = async (nickname: string, next?: string | null) => {
        try {
            const response = await authAPI.startSignup(nickname)
            rememberPendingRedirect(response.state, next)
            window.location.assign(response.authorizationUrl)
        } catch (error) {
            console.error("Google signup start failed:", error)
            throw error
        }
    }

    const completeGoogleAuthorization = async (code: string, state: string) => {
        try {
            const response = await authAPI.completeGoogleAuthorization(code, state)
            tokenManager.setTokens(response.data)
            setMemberId(response.data.memberId ?? null)
            setRole(response.data.role ?? null)
            setIsAuthenticated(true)
        } catch (error) {
            tokenManager.removeToken()
            setMemberId(null)
            setRole(null)
            setIsAuthenticated(false)
            console.error("Google authorization callback failed:", error)
            throw error
        }
    }

    const logout = async () => {
        try {
            await authAPI.logout()
        } catch (error) {
            console.error("Logout failed:", error)
        } finally {
            tokenManager.removeToken()
            setUser(null)
            setMemberId(null)
            setRole(null)
            setIsAuthenticated(false)
        }
    }

    const value: AuthContextType = {
        user,
        loading,
        startGoogleLogin,
        startGoogleSignup,
        completeGoogleAuthorization,
        logout,
        isAuthenticated,
        memberId,
        role,
    }

    return <AuthContext.Provider value={value}>
        {children}
        </AuthContext.Provider> ;
}

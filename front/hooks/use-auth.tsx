"use client"

import { useState, useEffect, useCallback, useRef, createContext, useContext } from "react"
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
    /**
     * 프로필 이미지의 blob URL. 없으면 null — 화면은 회색 플레이스홀더를 그린다.
     *
     * `<img>` 는 Authorization 헤더를 못 보내고 토큰은 localStorage 에 있으므로, 여기서 한 번
     * fetch 해 blob URL 로 만들어 헤더와 마이페이지가 함께 쓴다.
     */
    profileImageUrl: string | null
    /** 업로드·삭제 뒤에 부른다. 이전 blob URL 을 revoke 하고 다시 받는다. */
    refreshProfileImage: () => Promise<void>
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
    const [profileImageUrl, setProfileImageUrl] = useState<string | null>(null)

    // revoke 는 state 가 아니라 ref 로 붙잡는다 — 교체·언마운트 시점에 "직전 값"을 확실히
    // 알아야 하고, state 로 읽으면 클로저가 낡은 값을 잡는다. 놓치면 blob 이 그대로 새어
    // 페이지를 떠날 때까지 메모리에 남는다.
    const objectUrlRef = useRef<string | null>(null)

    const replaceObjectUrl = useCallback((next: string | null) => {
        if (objectUrlRef.current) {
            URL.revokeObjectURL(objectUrlRef.current)
        }
        objectUrlRef.current = next
        setProfileImageUrl(next)
    }, [])

    const refreshProfileImage = useCallback(async () => {
        if (!tokenManager.getToken()) {
            replaceObjectUrl(null)
            return
        }

        const blob = await authAPI.fetchProfileImageBlob()
        replaceObjectUrl(blob ? URL.createObjectURL(blob) : null)
    }, [replaceObjectUrl])

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

    // 인증 상태가 되면 아바타를 한 번 받아 온다. 헤더가 모든 라우트에 있으므로 여기서
    // 한 번만 받아 공유한다 — 화면마다 받으면 같은 이미지를 여러 번 가져온다.
    useEffect(() => {
        if (!isAuthenticated) {
            replaceObjectUrl(null)
            return
        }

        void refreshProfileImage()
    }, [isAuthenticated, refreshProfileImage, replaceObjectUrl])

    // 언마운트 시 정리. 이걸 빼면 blob 이 남는다.
    useEffect(() => () => {
        if (objectUrlRef.current) {
            URL.revokeObjectURL(objectUrlRef.current)
            objectUrlRef.current = null
        }
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
            replaceObjectUrl(null)
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
        profileImageUrl,
        refreshProfileImage,
    }

    return <AuthContext.Provider value={value}>
        {children}
        </AuthContext.Provider> ;
}

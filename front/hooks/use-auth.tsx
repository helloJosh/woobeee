"use client"

import { useState, useEffect, createContext, useContext } from "react"
import type { ReactNode } from "react"
import { authAPI, tokenManager } from "@/lib/api"
import type { User } from "@/lib/types"

interface AuthContextType {
    user: User | null
    loading: boolean
    startGoogleLogin: () => Promise<void>
    startGoogleSignup: (nickname: string) => Promise<void>
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

    const startGoogleLogin = async () => {
        try {
            const response = await authAPI.startGoogleLogin()
            window.location.assign(response.authorizationUrl)
        } catch (error) {
            console.error("Google login start failed:", error)
            throw error
        }
    }

    const startGoogleSignup = async (nickname: string) => {
        try {
            const response = await authAPI.startSignup(nickname)
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

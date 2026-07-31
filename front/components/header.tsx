"use client"

import type React from "react"

import {Search, Menu, Home, Sun, Moon, Github, Mail, LogIn, PackagePlus, Newspaper} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useTheme } from "next-themes"
import { useState, useEffect } from "react"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import Link from "next/link"
import UserMenu from "@/components/auth/user-menu"
import { useAuth } from "@/hooks/use-auth" // Updated import

interface HeaderProps {
  onToggleSidebar: () => void
  /** 상위에서 관리하는 검색어(있으면 표시됨) */
  searchQuery?: string | null
  /** 상위에 검색어 변경/제출을 알리고 싶을 때 사용 */
  onSearchChange?: (q: string) => void
  /** 선택사항: 홈 버튼 눌렀을 때 */
  onHome?: () => void
}

// 디바운싱을 위한 커스텀 훅
function useDebounce(value: string, delay: number) {
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedValue(value)
    }, delay)

    return () => {
      clearTimeout(handler)
    }
  }, [value, delay])

  return debouncedValue
}

export default function Header({
                                 onToggleSidebar,
                                 searchQuery: searchQueryProp,
                                 onSearchChange,
                                 onHome,
                               }: HeaderProps) {
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [searchQuery, setSearchQuery] = useState("")
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const debouncedSearchQuery = useDebounce(searchQuery, 300)
  const { user, loading, isAuthenticated, isSeller } = useAuth() // Updated usage

  // ▼ 언어 상태 추가 (ko-KR / en-US)
  const [lang, setLang] = useState<"ko-KR" | "en-US">("ko-KR")

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    // URL에서 검색어 읽기

    // @ts-ignore
    const query = searchParams.get("q")
    // @ts-ignore
    setSearchQuery(query)
  }, [searchParams])

  useEffect(() => {
    if (typeof searchQueryProp === "string") {
      setSearchQuery(searchQueryProp)
    }
  }, [searchQueryProp])

  // ▼ 최초 마운트 시 localStorage에 저장된 언어 불러오기
  useEffect(() => {
    if (!mounted) return
    const stored = localStorage.getItem("lang")
    if (stored === "ko-KR" || stored === "en-US") {
      setLang(stored)
    }
  }, [mounted])

  // ▼ 언어 변경될 때마다 localStorage 저장 + (필요하면 Axios 헤더 적용)
  useEffect(() => {
    if (!mounted) return
    localStorage.setItem("lang", lang)
  }, [lang, mounted])

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const q = searchQuery.trim()

    if (onSearchChange) {
      // 상위가 관리하는 방식: 상위에 위임
      console.log("[Header] 검색어 변경 감지:", q)
      onSearchChange(q)
    }
  }

  // ▼ EN/KR 토글 핸들러
  const handleToggleLang = () => {
    setLang((prev) => (prev === "ko-KR" ? "en-US" : "ko-KR"))
  }

  if (!mounted) {
    return null
  }

  return (
      <header className="sticky top-0 z-50 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex h-16 items-center px-4">
          <div className="flex items-center gap-4">
            <Button variant="ghost" size="icon" onClick={onToggleSidebar} className="md:hidden">
              <Menu className="h-5 w-5" />
            </Button>

            <Button variant="ghost" asChild className="flex items-center gap-2 font-semibold">
              <Link href="/">
                <Home className="h-5 w-5" />
                <span className="hidden sm:inline">마켓플레이스</span>
              </Link>
            </Button>

            <Button variant="ghost" asChild className="flex items-center gap-2 font-semibold">
              <Link href="/blog">
                <Newspaper className="h-5 w-5" />
                <span className="hidden sm:inline">블로그홈</span>
              </Link>
            </Button>
          </div>

          <div className="flex-1 flex items-center justify-end gap-4">
            <form onSubmit={handleSearchSubmit} className="relative max-w-sm w-full">
              <button
                  type="submit"
                  className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground"
              >
                <Search className="w-4 h-4" />
              </button>

              <Input
                  placeholder="검색..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
              />
            </form>

            {/* ▼ 여기 EN/KR 토글 버튼 추가 */}
            <Button
                variant="outline"
                size="sm"
                className="px-3"
                onClick={handleToggleLang}
            >
              {lang === "ko-KR" ? "KR" : "EN"}
            </Button>

            <Button variant="ghost" size="icon" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
              {theme === "dark" ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
            </Button>

            <Button variant="ghost" size="icon" asChild>
              <a href="https://github.com/helloJosh" target="_blank" rel="noopener noreferrer">
                <Github className="h-5 w-5" />
              </a>
            </Button>

            <Button variant="ghost" size="icon" asChild>
              <a href="mailto:kimjoshua135@gmail.com">
                <Mail className="h-5 w-5" />
              </a>
            </Button>

            {isSeller ? (
                <Button variant="outline" asChild className="flex items-center gap-2">
                  <Link href="/products/new">
                    <PackagePlus className="h-4 w-4" />
                    <span className="hidden sm:inline">상품등록</span>
                  </Link>
                </Button>
            ) : null}

            {/* 로그인/사용자 메뉴 */}
            {loading ? (
                <div className="h-8 w-8 rounded-full bg-muted animate-pulse" />
            ) : user ? (
                <UserMenu />
            ) : isAuthenticated ? (
                <Button variant="ghost" asChild className="flex items-center gap-2">
                  <Link href="/logout">
                    <LogIn className="h-4 w-4" />
                    <span className="hidden sm:inline">로그아웃</span>
                  </Link>
                </Button>
            ) : (
                <Button variant="ghost" asChild className="flex items-center gap-2">
                  <Link href="/login">
                    <LogIn className="h-4 w-4" />
                    <span className="hidden sm:inline">로그인</span>
                  </Link>
                </Button>
            )}
          </div>
        </div>
      </header>
  )
}

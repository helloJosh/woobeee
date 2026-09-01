"use client"

import type React from "react"

import {Search, Menu, Home, Sun, Moon, Github, Mail, LogIn, Newspaper, CalendarDays} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useTheme } from "next-themes"
import { useState, useEffect } from "react"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import Link from "next/link"
import ProfileAvatar from "@/components/auth/profile-avatar"
import { buildAuthHref, returnPathFor } from "@/lib/auth-redirect"
import { useAuth } from "@/hooks/use-auth" // Updated import
import { useHeaderControls } from "@/hooks/use-header-controls"

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

export default function Header() {
  // /blog처럼 사이드바·검색을 갖는 페이지가 마운트돼 있을 때만 채워진다.
  // 다른 라우트에서는 모두 undefined라서 아래 조건부 렌더링이 탭 두 개만 남긴다.
  const { onToggleSidebar, searchQuery: searchQueryProp, onSearchChange } = useHeaderControls()
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [searchQuery, setSearchQuery] = useState("")
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const debouncedSearchQuery = useDebounce(searchQuery, 300)
  const { loading, isAuthenticated, profileImageUrl } = useAuth()

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

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const q = searchQuery.trim()

    if (onSearchChange) {
      // 상위가 관리하는 방식: 상위에 위임
      console.log("[Header] 검색어 변경 감지:", q)
      onSearchChange(q)
    }
  }

  if (!mounted) {
    return null
  }

  return (
      <header className="sticky top-0 z-50 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex h-16 items-center px-4">
          <div className="flex items-center gap-4">
            {onToggleSidebar ? (
                <Button variant="ghost" size="icon" onClick={onToggleSidebar} className="md:hidden">
                  <Menu className="h-5 w-5" />
                </Button>
            ) : null}

            <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
              <Link href="/">
                <Home className="h-4 w-4" />
                <span className="hidden sm:inline">홈</span>
              </Link>
            </Button>
            <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
              <Link href="/blog">
                <Newspaper className="h-4 w-4" />
                <span className="hidden sm:inline">기술블로그</span>
              </Link>
            </Button>
            {isAuthenticated ? (
                <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
                  <Link href="/schedule">
                    <CalendarDays className="h-4 w-4" />
                    <span className="hidden sm:inline">일정</span>
                  </Link>
                </Button>
            ) : null}
          </div>

          <div className="flex-1 flex items-center justify-end gap-4">
            {onSearchChange ? (
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
            ) : null}

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

            {/* 로그인/사용자 메뉴 */}
            {loading ? (
                <div className="h-8 w-8 rounded-full bg-muted animate-pulse" />
            ) : isAuthenticated ? (
                /*
                 * 아바타 + 로그아웃. 아바타는 마이페이지로 가는 링크다 — 드롭다운을 두지 않는
                 * 것은 로그아웃이 헤더에 그대로 보여야 한다는 판단이다(한 번 더 누르게 만들지
                 * 않는다). 이미지가 없으면 회색 원 + 사람 아이콘이다.
                 */
                <div className="flex items-center gap-1">
                  <Link
                      href="/mypage"
                      aria-label="마이페이지"
                      className="rounded-full ring-offset-background transition-opacity hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                  >
                    <ProfileAvatar src={profileImageUrl} />
                  </Link>
                  <Button variant="ghost" asChild className="flex items-center gap-2">
                    <Link href="/logout">
                      <LogIn className="h-4 w-4" />
                      <span className="hidden sm:inline">로그아웃</span>
                    </Link>
                  </Button>
                </div>
            ) : (
                /*
                 * 헤더는 루트 레이아웃에 있어 모든 경로에서 렌더된다 — 초대 링크로 들어온
                 * 방문자가 보는 방 화면에서도, 참가 게이트 바로 위에서. 그 사람이 게이트의
                 * "로그인하고 참가" 대신 이 버튼을 누르는 것은 충분히 자연스럽고, 그때도
                 * 초대를 잃으면 안 된다. 그래서 목적지는 고정값이 아니라 "지금 있는 곳" 이다.
                 *
                 * pathname·searchParams 는 이 컴포넌트가 이미 검색어 때문에 읽고 있고,
                 * 레이아웃이 헤더를 Suspense 로 감싸 두었으므로 정적 페이지도 깨지지 않는다.
                 */
                <Button variant="ghost" asChild className="flex items-center gap-2">
                  <Link href={buildAuthHref("/login", returnPathFor(pathname, searchParams?.toString()))}>
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

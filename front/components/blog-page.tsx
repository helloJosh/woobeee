"use client"

import { useEffect, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import Sidebar from "@/components/sidebar"
import PostList from "@/components/post-list"
import { ThemeProvider } from "@/components/theme-provider"
import { Button } from "@/components/ui/button"
import { PenSquare } from "lucide-react"
import { tokenManager } from "@/lib/api"
import { canManagePosts } from "@/lib/blog-admin"
import { useCategories } from "@/hooks/use-categories"
import { useIsMobile } from "@/hooks/use-mobile"
import { useRegisterHeaderControls } from "@/hooks/use-header-controls"
import type { Post } from "@/lib/types"
import ChatWidget from "@/app/chat/page"

export default function BlogPage() {
  const { categories } = useCategories()
  const router = useRouter()
  const searchParams = useSearchParams()

  const [selectedCategory, setSelectedCategory] = useState<number | null>(searchParams.get("category") ? Number(searchParams.get("category")) : null)
  const [selectedCategoryName, setSelectedCategoryName] = useState<string | null>(searchParams.get("categoryName"))
  const [selectedPost, setSelectedPost] = useState<Post | null>(null)
  const [searchQuery, setSearchQuery] = useState<string | null>(searchParams.get("search"))
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const [sidebarWidth, setSidebarWidth] = useState(320)
  // localStorage는 서버 렌더에 없다 — 마운트 후에만 읽어 hydration 불일치를 피한다
  const [canWrite, setCanWrite] = useState(false)

  const isMobile = useIsMobile()

  useEffect(() => {
    setCanWrite(canManagePosts(tokenManager.getRole()))
  }, [])

  useEffect(() => {
    const category = searchParams.get("category")
    const query = searchParams.get("search")

    setSelectedCategory(category ? Number(category) : null)
    setSearchQuery(query ?? "")
  }, [searchParams])

  useEffect(() => {
    setSidebarOpen(!isMobile)
  }, [isMobile])

  const updateURL = (params: { category?: number | null; search?: string | null }) => {
    const newParams = new URLSearchParams()

    if (params.category) {
      newParams.set("category", String(params.category))
    }

    if (params.search) {
      newParams.set("search", params.search)
    }

    const queryString = newParams.toString()
    const newURL = queryString ? `/blog?${queryString}` : "/blog"
    router.push(newURL, { scroll: false })
  }

  const handleCategorySelect = (categoryId: number | null, categoryName: String) => {
    setSelectedCategory(categoryId)
    setSelectedCategoryName(String(categoryName))
    updateURL({ category: categoryId, search: searchQuery })
  }

  const handlePostSelect = (post: Post) => {
    setSelectedPost(post)
    router.push(`/blog/${post.id}`)
  }

  const handleHome = () => {
    setSelectedCategory(null)
    setSelectedPost(null)
    setSearchQuery("")
    router.push("/blog")
  }

  const handleSearchChange = (query: string) => {
    setSearchQuery(query)
    updateURL({ category: selectedCategory, search: query })
  }

  // 전역 헤더(root layout)에 이 페이지의 사이드바 토글·검색을 등록한다.
  // /blog를 벗어나면 훅의 클린업이 컨트롤을 비워서 헤더에 낡은 검색창이 남지 않는다.
  useRegisterHeaderControls({
    onToggleSidebar: () => setSidebarOpen(!sidebarOpen),
    searchQuery,
    onSearchChange: handleSearchChange,
  })

  return (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem>
      <div className="min-h-screen bg-background">
        <div className="flex">
          <Sidebar
            categories={categories}
            isOpen={sidebarOpen}
            width={sidebarWidth}
            onWidthChange={setSidebarWidth}
            onCategorySelect={handleCategorySelect}
          />

          <main className={`flex-1 transition-all duration-300 ${sidebarOpen ? "ml-80" : "ml-0"}`}>
            <div className="p-6">
              {canWrite && (
                <div className="flex justify-end mb-4">
                  <Button onClick={() => router.push("/blog/write")} className="flex items-center gap-2">
                    <PenSquare className="h-4 w-4" />
                    글쓰기
                  </Button>
                </div>
              )}
              <PostList
                selectedCategoryId={selectedCategory ?? undefined}
                selectedCategoryName={selectedCategoryName ?? undefined}
                searchQuery={searchQuery || undefined}
                onPostSelect={handlePostSelect}
              />
            </div>
          </main>
        </div>
      </div>

      <ChatWidget />
    </ThemeProvider>
  )
}

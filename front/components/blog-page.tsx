"use client"

import { useEffect, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import Header from "@/components/header"
import Sidebar from "@/components/sidebar"
import PostList from "@/components/post-list"
import { ThemeProvider } from "@/components/theme-provider"
import { useCategories } from "@/hooks/use-categories"
import { useIsMobile } from "@/hooks/use-mobile"
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

  const isMobile = useIsMobile()

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

  return (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem>
      <div className="min-h-screen bg-background">
        <Header
          onHome={handleHome}
          searchQuery={searchQuery}
          onSearchChange={handleSearchChange}
          onToggleSidebar={() => setSidebarOpen(!sidebarOpen)}
        />

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

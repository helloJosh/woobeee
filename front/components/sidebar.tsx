"use client"

import type React from "react"

import { useState, useRef, useCallback, useEffect } from "react"
import { ChevronDown, ChevronRight, Folder, FolderOpen, GripVertical } from "lucide-react"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import Link from "next/link"
import {usePathname, useSearchParams} from "next/navigation"
import type { Category } from "@/lib/types"

interface SidebarProps {
  categories: Category[]
  isOpen: boolean
  width: number
  onWidthChange: (width: number) => void
  onCategorySelect?: (id: number | null, categoryName: String) => void
  selectedCategoryId?: number
}

export default function Sidebar({
                                  categories,
                                  isOpen,
                                  width,
                                  onWidthChange,
                                  onCategorySelect,
                                  selectedCategoryId,
                                }: SidebarProps) {
  const [expandedCategories, setExpandedCategories] = useState<Set<number>>(new Set())
  const [isResizing, setIsResizing] = useState(false)
  const sidebarRef = useRef<HTMLDivElement>(null)
  const searchParams = useSearchParams()

  // @ts-ignore
  const selectedFromQuery = Number(searchParams.get("categoryId"))
  const effectiveSelectedId = selectedCategoryId ?? selectedFromQuery

  const handleCategoryClick = (categoryId: number, categoryName: String) => {
    // 폼 안에 있어도 submit되지 않도록 버튼에 type="button"을 지정하세요 (JSX 예시 아래 참고)
    onCategorySelect?.(categoryId, categoryName)
  }

  const handleAllClick = () => {
    // 선택 해제: 카테고리 ID와 이름을 비움
    onCategorySelect?.(null, "")
  }

  const toggleCategory = (categoryId: number) => {
    const newExpanded = new Set(expandedCategories)
    if (newExpanded.has(categoryId)) {
      newExpanded.delete(categoryId)
    } else {
      newExpanded.add(categoryId)
    }
    setExpandedCategories(newExpanded)
  }

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    setIsResizing(true)
    e.preventDefault()
  }, [])

  const handleMouseMove = useCallback(
    (e: MouseEvent) => {
      if (!isResizing) return

      const newWidth = e.clientX
      const minWidth = 200
      const maxWidth = 500

      if (newWidth >= minWidth && newWidth <= maxWidth) {
        onWidthChange(newWidth)
      }
    },
    [isResizing, onWidthChange],
  )

  const handleMouseUp = useCallback(() => {
    setIsResizing(false)
  }, [])

  useEffect(() => {
    if (!isResizing) return

    document.addEventListener("mousemove", handleMouseMove)
    document.addEventListener("mouseup", handleMouseUp)

    return () => {
      document.removeEventListener("mousemove", handleMouseMove)
      document.removeEventListener("mouseup", handleMouseUp)
    }
  }, [isResizing, handleMouseMove, handleMouseUp])

  const renderCategory = (category: Category, level = 0) => {
    const isExpanded = expandedCategories.has(category.id)

    const isSelected = (categoryId: number) => effectiveSelectedId === categoryId
    const hasChildren = category.children && category.children.length > 0

    return (
      <div key={category.id}>
        <div className="flex">
          {hasChildren && (
            <Button variant="ghost" size="sm" onClick={() => toggleCategory(category.id)} className="p-1 h-auto">
              {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </Button>
          )}

          <Button
            variant={"ghost"}
            className={`flex-1 justify-start gap-2 h-auto py-2 px-2 ${level > 0 ? `ml-${level * 4}` : ""} ${
              !hasChildren ? "ml-1" : ""
            }`}
            asChild
          >
            <div
                onClick={() => handleCategoryClick(category.id, category.name)} // ✅ 클릭 시 호출
            >
              {hasChildren ? (
                isExpanded ? (
                  <FolderOpen className="h-4 w-4" />
                ) : (
                  <Folder className="h-4 w-4" />
                )
              ) : (
                <div className="w-4" />
              )}
              <span className="text-left truncate">{category.name}</span>
              <span className="ml-auto text-xs text-muted-foreground">{category.count}</span>
            </div>
          </Button>
        </div>

        {hasChildren && isExpanded && (
          <div className="ml-4">{category.children?.map((child) => renderCategory(child, level + 1))}</div>
        )}
      </div>
    )
  }

  return (
    <>
      <aside
        ref={sidebarRef}
        className={`fixed left-0 top-16 h-[calc(100vh-4rem)] border-r bg-background transition-transform duration-300 z-40 ${
          isOpen ? "translate-x-0" : "-translate-x-full"
        }`}
        style={{ width: `${width}px` }}
      >
        <div className="p-4">
          <Button
              variant={"ghost"}
            className="w-full justify-start mb-2"
            asChild
          >
            <Link href="/blog" onClick={handleAllClick}>
              전체 글
            </Link>
          </Button>
        </div>

        <ScrollArea className="h-[calc(100%-8rem)] px-4">
          <div className="space-y-1">{categories.map((category) => renderCategory(category))}</div>
        </ScrollArea>

        {/* 크기 조절 핸들 */}
        <div
          className={`absolute top-0 right-0 w-1 h-full cursor-col-resize hover:bg-primary/20 transition-colors ${
            isResizing ? "bg-primary/30" : ""
          }`}
          onMouseDown={handleMouseDown}
        >
          {/*<div className="absolute top-1/2 right-0 transform -translate-y-1/2 translate-x-1/2">*/}
          {/*  <GripVertical className="h-4 w-4 text-muted-foreground" />*/}
          {/*</div>*/}
        </div>
      </aside>

      {/* 크기 조절 중일 때 오버레이 */}
      {isResizing && <div className="fixed inset-0 z-50 cursor-col-resize" style={{ userSelect: "none" }} />}
    </>
  )
}

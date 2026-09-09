"use client"

import { useEffect, useState, type ReactNode } from "react"
import { ChevronDown, ChevronRight } from "lucide-react"
import { categoryNav } from "@/lib/category-nav"
import type { PopularTag } from "@/lib/api"
import type { Category } from "@/lib/types"

const STORAGE_KEY = "home.categoriesOpen"

function SectionTitle({ children }: { children: ReactNode }) {
    return <h3 className="text-sm font-bold text-teal-500">{children}</h3>
}

function ItemButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
    return (
        <button type="button" onClick={onClick} aria-current={active ? "true" : undefined}
                className={`block w-full text-left text-[15px] leading-7 transition-colors hover:text-foreground ${
                    active ? "font-semibold text-foreground" : "text-foreground/80"
                }`}>
            {children}
        </button>
    )
}

/**
 * 오른쪽 사이드바 — 「카테고리」(접고 펼침, 기본 접힘, 선택은 브라우저에 기억)와 「태그」(이름 (글 수)).
 * 우아한 기술블로그의 오른쪽 열을 따르되 카테고리는 요청대로 옆에 숨겨 두었다.
 */
export default function HomeSidebar({ categories, selectedCategory, onSelectCategory, tags, activeTag, onSelectTag }: {
    categories: Category[]
    selectedCategory: number | null
    onSelectCategory: (id: number | null) => void
    tags: PopularTag[]
    activeTag: string | null
    onSelectTag: (name: string) => void
}) {
    const [open, setOpen] = useState(false)
    useEffect(() => {
        try { setOpen(localStorage.getItem(STORAGE_KEY) === "1") } catch { /* 저장소 없음 — 기본 접힘 */ }
    }, [])
    const toggle = () => {
        const next = !open
        setOpen(next)
        try { localStorage.setItem(STORAGE_KEY, next ? "1" : "0") } catch { /* 무시 */ }
    }

    const nav = categoryNav(categories, selectedCategory)
    const selectedName = selectedCategory === null ? null
        : categories.flatMap((c) => [c, ...(c.children ?? [])]).find((c) => c.id === selectedCategory)?.name ?? null

    return (
        <aside className="space-y-10 lg:sticky lg:top-24">
            <section aria-label="카테고리" className="space-y-2">
                <button type="button" onClick={toggle} aria-expanded={open}
                        className="flex w-full items-center justify-between gap-2 text-left">
                    <SectionTitle>카테고리</SectionTitle>
                    <span className="flex items-center gap-1 text-xs text-muted-foreground">
                        {!open && selectedName ? <span className="max-w-[9rem] truncate">{selectedName}</span> : null}
                        {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                    </span>
                </button>
                {open ? (
                    <ul className="space-y-0.5">
                        <li><ItemButton active={selectedCategory === null} onClick={() => onSelectCategory(null)}>전체</ItemButton></li>
                        {nav.parents.map((c) => (
                            <li key={c.id}>
                                <ItemButton active={selectedCategory === c.id} onClick={() => onSelectCategory(c.id)}>
                                    {c.name}{c.count > 0 ? <span className="ml-1 text-muted-foreground">({c.count})</span> : null}
                                </ItemButton>
                                {nav.activeParent?.id === c.id && nav.children.length > 0 ? (
                                    <ul className="ml-3 border-l pl-3">
                                        {nav.children.map((child) => (
                                            <li key={child.id}>
                                                <ItemButton active={selectedCategory === child.id} onClick={() => onSelectCategory(child.id)}>
                                                    {child.name}{child.count > 0 ? <span className="ml-1 text-muted-foreground">({child.count})</span> : null}
                                                </ItemButton>
                                            </li>
                                        ))}
                                    </ul>
                                ) : null}
                            </li>
                        ))}
                    </ul>
                ) : null}
            </section>

            {tags.length > 0 ? (
                <section aria-label="태그" className="space-y-2">
                    <SectionTitle>태그</SectionTitle>
                    <ul className="space-y-0.5">
                        {tags.map((t) => (
                            <li key={t.id}>
                                <ItemButton active={activeTag !== null && activeTag.toLowerCase() === t.name.toLowerCase()}
                                            onClick={() => onSelectTag(t.name)}>
                                    {t.name} <span className="text-muted-foreground">({t.count})</span>
                                </ItemButton>
                            </li>
                        ))}
                    </ul>
                </section>
            ) : null}
        </aside>
    )
}

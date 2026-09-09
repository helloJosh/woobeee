"use client"

import type { ReactNode } from "react"
import { categoryNav } from "@/lib/category-nav"
import type { Category } from "@/lib/types"

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-pressed={active}
            className={`shrink-0 rounded-full px-3.5 py-1.5 text-sm font-medium transition-colors ${
                active ? "bg-foreground text-background" : "bg-muted text-muted-foreground hover:bg-muted/70 hover:text-foreground"
            }`}
        >
            {children}
        </button>
    )
}

/** 상단 카테고리 내비 — 부모 chips 한 줄, 고른 부모의 자식 chips 한 줄. 가로 스크롤. */
export default function CategoryBar({ categories, selectedId, onSelect }: {
    categories: Category[]
    selectedId: number | null
    onSelect: (id: number | null) => void
}) {
    const nav = categoryNav(categories, selectedId)
    return (
        <nav aria-label="카테고리" className="space-y-2">
            <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                <Chip active={selectedId === null} onClick={() => onSelect(null)}>전체</Chip>
                {nav.parents.map((c) => (
                    <Chip key={c.id} active={nav.activeParent?.id === c.id} onClick={() => onSelect(c.id)}>
                        {c.name}{c.count > 0 ? <span className="ml-1 text-xs opacity-70">{c.count}</span> : null}
                    </Chip>
                ))}
            </div>
            {nav.children.length > 0 && nav.activeParent ? (
                <div className="flex gap-2 overflow-x-auto pb-1 pl-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                    <Chip active={selectedId === nav.activeParent.id} onClick={() => onSelect(nav.activeParent!.id)}>
                        {nav.activeParent.name} 전체
                    </Chip>
                    {nav.children.map((c) => (
                        <Chip key={c.id} active={selectedId === c.id} onClick={() => onSelect(c.id)}>
                            {c.name}{c.count > 0 ? <span className="ml-1 text-xs opacity-70">{c.count}</span> : null}
                        </Chip>
                    ))}
                </div>
            ) : null}
        </nav>
    )
}

"use client"

import type { Tag } from "@/lib/types"

/** 글에 붙은 태그 chips. 클릭하면 홈이 그 태그로 필터된다(카드 링크와 분리된 버튼이라 중첩 링크가 없다). */
export default function TagChips({ tags, activeTag, onSelect, className = "" }: {
    tags: Tag[] | undefined
    activeTag?: string | null
    onSelect: (name: string) => void
    className?: string
}) {
    if (!tags || tags.length === 0) return null
    return (
        <div className={`flex flex-wrap gap-1.5 ${className}`}>
            {tags.map((t) => {
                const active = activeTag !== null && activeTag !== undefined && activeTag.toLowerCase() === t.name.toLowerCase()
                return (
                    <button
                        key={t.id}
                        type="button"
                        onClick={() => onSelect(t.name)}
                        className={`rounded-full border px-2 py-0.5 text-xs transition-colors hover:bg-muted ${
                            active ? "border-foreground bg-foreground text-background hover:bg-foreground/90" : "text-muted-foreground"
                        }`}
                    >
                        #{t.name}
                    </button>
                )
            })}
        </div>
    )
}

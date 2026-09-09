"use client"

import type { ReactNode } from "react"
import type { PopularTag } from "@/lib/api"

function Pill({ active, onClick, children, label }: { active: boolean; onClick: () => void; children: ReactNode; label?: string }) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-pressed={active}
            aria-label={label}
            className={`rounded-full border px-5 py-2 text-[15px] transition-colors ${
                active ? "border-foreground bg-foreground text-background" : "border-border bg-background hover:border-foreground/60"
            }`}
        >
            {children}
        </button>
    )
}

/** 상단 태그 줄 — 우아한 기술블로그의 알약 내비. 첫 칸 「-」은 전체(태그 해제). 가운데 정렬, 줄바꿈. */
export default function TagBar({ tags, activeTag, onSelectTag, onClear }: {
    tags: PopularTag[]
    activeTag: string | null
    onSelectTag: (name: string) => void
    onClear: () => void
}) {
    if (tags.length === 0) return null
    return (
        <nav aria-label="태그" className="flex flex-wrap justify-center gap-x-3 gap-y-3 py-6 sm:py-10">
            <Pill active={activeTag === null} onClick={onClear} label="전체">-</Pill>
            {tags.map((t) => (
                <Pill key={t.id} active={activeTag !== null && activeTag.toLowerCase() === t.name.toLowerCase()}
                      onClick={() => onSelectTag(t.name)}>
                    {t.name}
                </Pill>
            ))}
        </nav>
    )
}

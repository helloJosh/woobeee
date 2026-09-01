"use client"

import { useEffect, useRef, useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { formatDateRange } from "@/lib/schedule"

interface Props {
    /** 달력 컨테이너 기준 좌표 — 호출부가 화면 밖으로 나가지 않게 보정해서 준다. */
    x: number
    y: number
    startIso: string
    endIso: string
    /** "「DM」 프로젝트 소속" / "바로 할 일" 같은 소속 안내. */
    parentLabel: string
    onSubmit: (name: string) => Promise<void>
    onClose: () => void
}

/** 애플 캘린더식 빠른 생성 팝오버 — 달력 드래그/클릭 직후 선택 범위 옆에 뜬다 (SCHEDULE-AC-32). */
export default function QuickTaskPopover({ x, y, startIso, endIso, parentLabel, onSubmit, onClose }: Props) {
    const [name, setName] = useState("")
    const [saving, setSaving] = useState(false)
    const cardRef = useRef<HTMLDivElement>(null)

    useEffect(() => {
        const onMouseDown = (e: MouseEvent) => {
            if (cardRef.current && !cardRef.current.contains(e.target as Node)) onClose()
        }
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.key === "Escape") onClose()
        }
        window.addEventListener("mousedown", onMouseDown)
        window.addEventListener("keydown", onKeyDown)
        return () => {
            window.removeEventListener("mousedown", onMouseDown)
            window.removeEventListener("keydown", onKeyDown)
        }
    }, [onClose])

    const submit = async () => {
        const trimmed = name.trim()
        if (trimmed === "" || saving) return
        setSaving(true)
        try {
            await onSubmit(trimmed)
        } finally {
            setSaving(false)
        }
    }

    return (
        <div
            ref={cardRef}
            className="absolute z-20 w-72 rounded-lg border bg-popover p-3 text-popover-foreground shadow-xl"
            style={{ left: `${x}px`, top: `${y}px` }}
            onMouseDown={(e) => e.stopPropagation()}
        >
            <Input
                autoFocus
                placeholder="새 할 일"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === "Enter") void submit()
                }}
            />
            <div className="mt-2 space-y-0.5 text-xs text-muted-foreground">
                <p>{formatDateRange(startIso, endIso)}</p>
                <p>{parentLabel}</p>
            </div>
            <div className="mt-3 flex justify-end gap-2">
                <Button variant="outline" size="sm" onClick={onClose} disabled={saving}>취소</Button>
                <Button size="sm" onClick={() => void submit()} disabled={saving || name.trim() === ""}>
                    {saving ? "저장 중..." : "추가"}
                </Button>
            </div>
        </div>
    )
}

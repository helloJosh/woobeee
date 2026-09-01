"use client"

import { useEffect, useRef, useState } from "react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
    isValidDateRange, nextStatus, STATUS_LABELS,
    type ScheduleItemKind, type ScheduleStatus,
} from "@/lib/schedule"

export interface EntryDraft {
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
}

interface Props {
    x: number
    y: number
    kind: ScheduleItemKind
    initial: EntryDraft
    onSubmit: (draft: EntryDraft) => Promise<void>
    onDelete: () => void
    onClose: () => void
}

const KIND_LABEL: Record<ScheduleItemKind, string> = {
    project: "프로젝트",
    milestone: "마일스톤",
    task: "할일",
}

/** 달력 막대 클릭 시 옆에 뜨는 수정 팝오버 — 빠른 생성 팝오버와 같은 상호작용. */
export default function EntryEditPopover({ x, y, kind, initial, onSubmit, onDelete, onClose }: Props) {
    const [draft, setDraft] = useState<EntryDraft>(initial)
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

    const invalidRange = !isValidDateRange(draft.startDate, draft.endDate)
    const disabled = saving || draft.name.trim() === "" || invalidRange

    const submit = async () => {
        if (disabled) return
        setSaving(true)
        try {
            await onSubmit({ ...draft, name: draft.name.trim() })
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
            <div className="mb-2 flex items-center gap-2">
                <span className="text-xs text-muted-foreground">[{KIND_LABEL[kind]}]</span>
                <button type="button" title="클릭하면 상태가 바뀝니다" aria-label="상태 변경"
                        onClick={() => setDraft({ ...draft, status: nextStatus(draft.status) })}>
                    <Badge variant="outline">{STATUS_LABELS[draft.status]}</Badge>
                </button>
            </div>
            <Input
                autoFocus
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                onKeyDown={(e) => {
                    if (e.key === "Enter") void submit()
                }}
            />
            <div className="mt-2 grid grid-cols-2 gap-2">
                <Input type="date" aria-label="시작일" value={draft.startDate ?? ""}
                       onChange={(e) => setDraft({ ...draft, startDate: e.target.value || null })} />
                <Input type="date" aria-label="종료일" value={draft.endDate ?? ""}
                       onChange={(e) => setDraft({ ...draft, endDate: e.target.value || null })} />
            </div>
            {invalidRange ? (
                <p className="mt-1 text-xs text-destructive">종료일은 시작일보다 빠를 수 없습니다.</p>
            ) : null}
            <div className="mt-3 flex items-center justify-between">
                <Button variant="outline" size="sm" className="text-destructive" onClick={onDelete} disabled={saving}>
                    삭제
                </Button>
                <div className="flex gap-2">
                    <Button variant="outline" size="sm" onClick={onClose} disabled={saving}>취소</Button>
                    <Button size="sm" onClick={() => void submit()} disabled={disabled}>
                        {saving ? "저장 중..." : "저장"}
                    </Button>
                </div>
            </div>
        </div>
    )
}

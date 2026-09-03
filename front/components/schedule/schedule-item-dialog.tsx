"use client"

import { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { SCHEDULE_COLORS, STATUS_LABELS, isValidDateRange, isValidHexColor, type ScheduleStatus } from "@/lib/schedule"

export type ItemKind = "project" | "milestone" | "task"

export interface ItemDraft {
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    color?: string // task 수정에서만
}

interface Props {
    open: boolean
    kind: ItemKind
    title: string // "새 프로젝트", "할 일 수정" 등 — 호출부가 정한다
    /** 생성 시 무엇을 어디에 만드는지 안내 — 예: "「DM」 프로젝트 아래에 추가 — ...". */
    context?: string
    initial: ItemDraft
    showColor: boolean // task 수정에서만 true (생성 색은 서버가 배정)
    onSubmit: (draft: ItemDraft) => Promise<void>
    onClose: () => void
}

const STATUSES: ScheduleStatus[] = ["NOT_STARTED", "IN_PROGRESS", "DONE"]

export default function ScheduleItemDialog({ open, kind, title, context, initial, showColor, onSubmit, onClose }: Props) {
    const [draft, setDraft] = useState<ItemDraft>(initial)
    const [saving, setSaving] = useState(false)

    useEffect(() => {
        if (open) setDraft(initial)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open])

    const invalidRange = !isValidDateRange(draft.startDate, draft.endDate)
    const invalidColor = showColor && draft.color !== undefined && !isValidHexColor(draft.color)
    const disabled = saving || draft.name.trim() === "" || invalidRange || invalidColor

    const submit = async () => {
        setSaving(true)
        try {
            await onSubmit({ ...draft, name: draft.name.trim() })
            onClose()
        } finally {
            setSaving(false)
        }
    }

    return (
        <Dialog open={open} onOpenChange={(v) => { if (!v) onClose() }}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
                {context ? <p className="text-sm text-muted-foreground">{context}</p> : null}
                <div className="space-y-4">
                    <div className="space-y-2">
                        <Label htmlFor="schedule-name">이름</Label>
                        <Input id="schedule-name" value={draft.name} maxLength={200}
                               onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                    </div>
                    <div className="space-y-2">
                        <Label>상태</Label>
                        <Select value={draft.status}
                                onValueChange={(v) => setDraft({ ...draft, status: v as ScheduleStatus })}>
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {STATUSES.map((s) => (
                                    <SelectItem key={s} value={s}>{STATUS_LABELS[s]}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-2">
                            <Label htmlFor="schedule-start">시작일 (비우면 미정)</Label>
                            <Input id="schedule-start" type="date" value={draft.startDate ?? ""}
                                   onChange={(e) => setDraft({ ...draft, startDate: e.target.value || null })} />
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="schedule-end">종료일 (비우면 미정)</Label>
                            <Input id="schedule-end" type="date" value={draft.endDate ?? ""}
                                   onChange={(e) => setDraft({ ...draft, endDate: e.target.value || null })} />
                        </div>
                    </div>
                    {invalidRange ? (
                        <p className="text-sm text-destructive">종료일은 시작일보다 빠를 수 없습니다.</p>
                    ) : null}
                    {showColor ? (
                        <div className="space-y-2">
                            <Label>색상</Label>
                            <div className="grid grid-cols-12 gap-2">
                                {SCHEDULE_COLORS.map((c) => (
                                    <button key={c} type="button" aria-label={`색 ${c}`}
                                            className={`h-6 w-6 rounded-full border-2 ${draft.color === c ? "border-foreground" : "border-transparent"}`}
                                            style={{ backgroundColor: c }}
                                            onClick={() => setDraft({ ...draft, color: c })} />
                                ))}
                            </div>
                            <Input value={draft.color ?? ""} placeholder="#RRGGBB"
                                   onChange={(e) => setDraft({ ...draft, color: e.target.value })} />
                            {invalidColor ? (
                                <p className="text-sm text-destructive">색상은 #RRGGBB 형식이어야 합니다.</p>
                            ) : null}
                        </div>
                    ) : null}
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={onClose} disabled={saving}>취소</Button>
                    <Button onClick={submit} disabled={disabled}>{saving ? "저장 중..." : "저장"}</Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    )
}

"use client"

import { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import {
    REMINDER_OPTIONS, SCHEDULE_COLORS, STATUS_LABELS,
    canRemind, isValidHexColor, isValidTimeRange, reminderLabel,
    type ScheduleStatus,
} from "@/lib/schedule"

export type ItemKind = "project" | "milestone" | "task"

export interface ItemDraft {
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    /** 할 일만 — "HH:mm" 또는 null (SCHEDULE-AC-34). */
    startTime?: string | null
    endTime?: string | null
    /** 할 일만 — 시작 전 알림(분) (SCHEDULE-AC-35). */
    reminders?: number[]
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
    /** Slack webhook 등록 여부 — false 면 알림이 발송되지 않는다고 안내한다. null 은 아직 모름. */
    slackConfigured?: boolean | null
    onSubmit: (draft: ItemDraft) => Promise<void>
    onClose: () => void
}

const STATUSES: ScheduleStatus[] = ["NOT_STARTED", "IN_PROGRESS", "DONE"]

export default function ScheduleItemDialog({ open, kind, title, context, initial, showColor, slackConfigured, onSubmit, onClose }: Props) {
    const [draft, setDraft] = useState<ItemDraft>(initial)
    const [saving, setSaving] = useState(false)

    useEffect(() => {
        if (open) setDraft(initial)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open])

    const isTask = kind === "task"
    const startTime = draft.startTime ?? null
    const endTime = draft.endTime ?? null
    const reminders = draft.reminders ?? []
    const invalidRange = !isValidTimeRange(draft.startDate, draft.endDate, isTask ? startTime : null, isTask ? endTime : null)
    const invalidColor = showColor && draft.color !== undefined && !isValidHexColor(draft.color)
    const remindable = canRemind(draft.startDate, startTime)
    const disabled = saving || draft.name.trim() === "" || invalidRange || invalidColor

    // 날짜를 비우면 그쪽 시간도 비운다. 시작 일시가 사라지면 알림도 비운다 (SCHEDULE-AC-34/35).
    const setStartDate = (v: string) => {
        const startDate = v || null
        setDraft({ ...draft, startDate, startTime: startDate ? startTime : null, reminders: startDate ? reminders : [] })
    }
    const setEndDate = (v: string) => {
        const endDate = v || null
        setDraft({ ...draft, endDate, endTime: endDate ? endTime : null })
    }
    const setStartTime = (v: string) => {
        const t = v || null
        setDraft({ ...draft, startTime: t, reminders: t ? reminders : [] })
    }
    const toggleReminder = (minutes: number, on: boolean) => {
        const next = on ? [...reminders.filter((m) => m !== minutes), minutes] : reminders.filter((m) => m !== minutes)
        setDraft({ ...draft, reminders: next.sort((a, b) => a - b) })
    }

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
                                   onChange={(e) => setStartDate(e.target.value)} />
                            {isTask ? (
                                <Input type="time" aria-label="시작 시간" value={startTime ?? ""}
                                       disabled={!draft.startDate}
                                       onChange={(e) => setStartTime(e.target.value)} />
                            ) : null}
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="schedule-end">종료일 (비우면 미정)</Label>
                            <Input id="schedule-end" type="date" value={draft.endDate ?? ""}
                                   onChange={(e) => setEndDate(e.target.value)} />
                            {isTask ? (
                                <Input type="time" aria-label="종료 시간" value={endTime ?? ""}
                                       disabled={!draft.endDate}
                                       onChange={(e) => setDraft({ ...draft, endTime: e.target.value || null })} />
                            ) : null}
                        </div>
                    </div>
                    {invalidRange ? (
                        <p className="text-sm text-destructive">종료는 시작보다 빠를 수 없습니다.</p>
                    ) : null}
                    {isTask ? (
                        <div className="space-y-2">
                            <Label>시작 전 알림</Label>
                            <div className="flex flex-wrap gap-4">
                                {REMINDER_OPTIONS.map((m) => (
                                    <label key={m} className={`flex items-center gap-2 text-sm ${remindable ? "" : "text-muted-foreground"}`}>
                                        <Checkbox checked={reminders.includes(m)} disabled={!remindable}
                                                  aria-label={reminderLabel(m)}
                                                  onCheckedChange={(v) => toggleReminder(m, v === true)} />
                                        {reminderLabel(m)}
                                    </label>
                                ))}
                            </div>
                            {!remindable ? (
                                <p className="text-xs text-muted-foreground">시작일과 시작 시간을 입력하면 선택할 수 있습니다.</p>
                            ) : slackConfigured === false && reminders.length > 0 ? (
                                <p className="text-xs text-destructive">Slack 알림이 등록되어 있지 않아 발송되지 않습니다. 상단 「알림」에서 먼저 등록하세요.</p>
                            ) : reminders.length > 0 ? (
                                <p className="text-xs text-muted-foreground">등록된 Slack 으로 발송됩니다.</p>
                            ) : null}
                        </div>
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

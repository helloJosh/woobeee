"use client"

import type React from "react"
import { useEffect, useRef, useState } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
    applyBarDrag, calendarLayout, draggedRange, orderedRange,
    type BarDrag, type BarDragMode, type CalendarWeek,
} from "@/lib/schedule-calendar"
import EntryEditPopover, { type EntryDraft } from "@/components/schedule/entry-edit-popover"
import QuickTaskPopover from "@/components/schedule/quick-task-popover"
import { todayIso, type CalendarEntry, type ScheduleItemKind } from "@/lib/schedule"

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"]
/** 모든 막대 공통 높이 — 종류 구분은 색·라벨·글씨로 한다. */
const BAR_HEIGHT = 14
/** 같은 프로젝트 그룹 안에서는 살짝 붙인다. */
const ATTACH_GAP = 1
/** 프로젝트 그룹 사이 간격. */
const GROUP_GAP = 8
const WEEK_HEADER = 24
const WEEK_PAD = 6
/** 막대가 없어도 주 칸은 예전 크기를 유지한다. */
const MIN_WEEK_HEIGHT = 116

/** lane → 세로 위치·높이. 그룹 시작 lane 앞에만 GROUP_GAP, 나머지는 붙인다 (SCHEDULE-AC-30). */
function laneMetrics(week: CalendarWeek): { tops: number[]; heights: number[]; contentBottom: number } {
    const tops: number[] = []
    const heights: number[] = []
    let y = WEEK_HEADER
    for (const seg of week.segments) {
        if (seg.lane > 0) y += week.laneGroupStarts[seg.lane] ? GROUP_GAP : ATTACH_GAP
        tops.push(y)
        const h = BAR_HEIGHT
        heights.push(h)
        y += h
    }
    return { tops, heights, contentBottom: y }
}

const NEUTRAL_KIND_CLASS: Record<Exclude<ScheduleItemKind, "task">, string> = {
    project: "bg-slate-600 text-white font-semibold",
    milestone: "bg-slate-400 text-slate-950",
}

const KIND_LABEL: Record<ScheduleItemKind, string> = {
    project: "프로젝트",
    milestone: "마일스톤",
    task: "할일",
}

/** 빈 칸에서 시작한 생성 드래그 (SCHEDULE-AC-32). 막대 위 드래그는 BarDrag 로 간다 (SCHEDULE-AC-33). */
interface DragState {
    anchor: string
    current: string
}

interface QuickCreateState {
    startIso: string
    endIso: string
    x: number
    y: number
}

/** 팝오버가 놓인 지점 옆에 뜨도록 섹션 기준 좌표를 계산한다. */
function popoverAt(section: HTMLElement | null, clientX: number, clientY: number): { x: number; y: number } {
    const rect = section?.getBoundingClientRect()
    const POPOVER_WIDTH = 288 // w-72
    const x = rect ? Math.min(Math.max(clientX - rect.left + 8, 8), rect.width - POPOVER_WIDTH - 8) : 8
    const y = rect ? Math.max(clientY - rect.top + 8, 8) : 8
    return { x, y }
}

interface EditState {
    kind: ScheduleItemKind
    id: number
    x: number
    y: number
}

export default function ScheduleCalendar({ entries, year, month, onMove, onEntrySave, onEntryDelete, onQuickCreate }: {
    entries: CalendarEntry[]
    year: number
    month: number // 1~12
    onMove: (year: number, month: number) => void
    /** 막대 수정 팝오버 저장/삭제 — 페이지가 API 호출과 갱신을 담당한다. */
    onEntrySave: (kind: ScheduleItemKind, id: number, draft: EntryDraft) => Promise<void>
    onEntryDelete: (kind: ScheduleItemKind, id: number) => void
    /** SCHEDULE-AC-32 — 팝오버에서 저장 시 호출. projectId null = 무소속. */
    onQuickCreate: (name: string, startIso: string, endIso: string, projectId: number | null) => Promise<void>
}) {
    const today = todayIso()
    const [drag, setDrag] = useState<DragState | null>(null)
    const dragRef = useRef<DragState | null>(null)
    dragRef.current = drag
    // SCHEDULE-AC-33 — 막대 이동·리사이즈 드래그. committed 는 놓은 뒤 저장이 끝날 때까지 미리보기를 유지한다(옵티미스틱).
    const [barDrag, setBarDrag] = useState<BarDrag | null>(null)
    const barDragRef = useRef<BarDrag | null>(null)
    barDragRef.current = barDrag
    const [committedDrag, setCommittedDrag] = useState<BarDrag | null>(null)
    const entriesRef = useRef(entries)
    entriesRef.current = entries
    const onEntrySaveRef = useRef(onEntrySave)
    onEntrySaveRef.current = onEntrySave
    const suppressClickRef = useRef(false)
    const [quickCreate, setQuickCreate] = useState<QuickCreateState | null>(null)
    const [edit, setEdit] = useState<EditState | null>(null)
    const sectionRef = useRef<HTMLElement>(null)

    // 끄는 동안(과 저장 중)은 옮겨진 자리에 그린다
    const weeks = calendarLayout(applyBarDrag(entries, barDrag ?? committedDrag), year, month)

    // 마우스를 달력 밖에서 놓아도 드래그를 끝낸다 — 생성 드래그는 팝오버, 막대 드래그는 즉시 저장
    useEffect(() => {
        const finish = (e: MouseEvent) => {
            const b = barDragRef.current
            if (b) {
                setBarDrag(null)
                // 움직임 없이 놓으면 클릭(수정)으로 넘긴다
                if (b.anchor === b.current) return
                suppressClickRef.current = true
                const target = entriesRef.current.find((en) => en.kind === b.kind && en.id === b.id)
                if (!target) return
                setCommittedDrag(b)
                const range = draggedRange(target, b)
                void onEntrySaveRef.current(b.kind, b.id, { name: target.name, status: target.status, ...range })
                    .catch(() => { /* 실패 시 원위치 — 아래 finally 가 미리보기를 걷는다 */ })
                    .finally(() => setCommittedDrag(null))
                return
            }
            const d = dragRef.current
            if (!d) return
            setDrag(null)
            const { start, end } = orderedRange(d.anchor, d.current)
            setQuickCreate({ startIso: start, endIso: end, ...popoverAt(sectionRef.current, e.clientX, e.clientY) })
        }
        // Esc 로 진행 중인 드래그를 취소한다
        const cancel = (e: KeyboardEvent) => {
            if (e.key !== "Escape") return
            if (barDragRef.current) {
                suppressClickRef.current = barDragRef.current.anchor !== barDragRef.current.current
                setBarDrag(null)
            }
            if (dragRef.current) setDrag(null)
        }
        window.addEventListener("mouseup", finish)
        window.addEventListener("keydown", cancel)
        return () => {
            window.removeEventListener("mouseup", finish)
            window.removeEventListener("keydown", cancel)
        }
    }, [])

    /** 주 컨테이너 내부 x 좌표 → 그 칸의 실제 날짜. */
    const isoAt = (e: React.MouseEvent<HTMLDivElement>, week: CalendarWeek): string => {
        const rect = e.currentTarget.getBoundingClientRect()
        const col = Math.min(6, Math.max(0, Math.floor(((e.clientX - rect.left) / rect.width) * 7)))
        return week.days[col].iso
    }

    const beginDrag = (e: React.MouseEvent<HTMLDivElement>, week: CalendarWeek) => {
        if (e.button !== 0) return
        if (quickCreate || edit) return // 팝오버가 열려 있으면 바깥 클릭은 닫기가 처리한다
        if (committedDrag) return // 저장 중에는 새 드래그를 받지 않는다
        const target = e.target as HTMLElement
        const bar = target.closest<HTMLElement>("[data-bar-kind]")
        const iso = isoAt(e, week)
        e.preventDefault() // 텍스트 선택 방지
        if (bar) {
            // 막대 위 드래그 = 이동, 양끝 손잡이 = 그 끝만 리사이즈 (SCHEDULE-AC-33)
            const handle = target.closest<HTMLElement>("[data-bar-handle]")?.dataset.barHandle
            const mode: BarDragMode = handle === "start" ? "resize-start" : handle === "end" ? "resize-end" : "move"
            setBarDrag({ kind: bar.dataset.barKind as ScheduleItemKind, id: Number(bar.dataset.barId), mode, anchor: iso, current: iso })
        } else {
            setDrag({ anchor: iso, current: iso })
        }
    }

    const updateDrag = (e: React.MouseEvent<HTMLDivElement>, week: CalendarWeek) => {
        const iso = isoAt(e, week)
        if (barDragRef.current) {
            if (iso !== barDragRef.current.current) setBarDrag({ ...barDragRef.current, current: iso })
            return
        }
        if (!dragRef.current) return
        if (iso !== dragRef.current.current) {
            setDrag({ ...dragRef.current, current: iso })
        }
    }

    /** 시작·종료일이 둘 다 있는 막대만 양끝 손잡이를 가진다. */
    const resizable = (kind: ScheduleItemKind, id: number): boolean => {
        const en = entries.find((x) => x.kind === kind && x.id === id)
        return !!en && en.startDate !== null && en.endDate !== null
    }
    const dragCursor = barDrag ? (barDrag.mode === "move" ? "cursor-grabbing" : "cursor-col-resize") : ""

    const dragRange = drag
        ? orderedRange(drag.anchor, drag.current)
        : quickCreate
            ? { start: quickCreate.startIso, end: quickCreate.endIso }
            : null

    const quickCreateParentLabel = "어느 프로젝트에도 속하지 않는 바로 할 일"

    const move = (delta: number) => {
        const d = new Date(year, month - 1 + delta, 1)
        onMove(d.getFullYear(), d.getMonth() + 1)
    }

    return (
        <section ref={sectionRef} className={`relative rounded-lg border ${dragCursor}`}>
            <div className="flex items-center justify-between border-b px-3 py-2">
                <h2 className="text-sm font-semibold">{year}년 {month}월</h2>
                <div className="flex gap-1">
                    <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="이전 달" onClick={() => move(-1)}>
                        <ChevronLeft className="h-4 w-4" />
                    </Button>
                    <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="다음 달" onClick={() => move(1)}>
                        <ChevronRight className="h-4 w-4" />
                    </Button>
                </div>
            </div>
            <div className="grid grid-cols-7 border-b text-center text-xs text-muted-foreground">
                {WEEKDAYS.map((d) => <div key={d} className="py-1">{d}</div>)}
            </div>
            {weeks.map((week, wi) => {
                const { tops, heights, contentBottom } = laneMetrics(week)
                const minHeight = Math.max(MIN_WEEK_HEIGHT, contentBottom + WEEK_PAD)
                return (
                    <div key={wi} className="relative grid select-none grid-cols-7 border-b last:border-b-0"
                         style={{ minHeight: `${minHeight}px` }}
                         onMouseDown={(e) => beginDrag(e, week)}
                         onMouseMove={(e) => updateDrag(e, week)}>
                        {week.days.map((day, di) => (
                            <div key={di}
                                 className={`border-r p-1 text-xs last:border-r-0 ${
                                     dragRange && day.iso >= dragRange.start && day.iso <= dragRange.end
                                         ? "bg-accent/60" : ""
                                 } ${day.inMonth ? "text-muted-foreground" : "text-muted-foreground/40"}`}>
                                {day.iso === today ? (
                                    <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary font-semibold text-primary-foreground">
                                        {day.date}
                                    </span>
                                ) : (
                                    day.date
                                )}
                            </div>
                        ))}
                        {week.segments.map((seg, si) => (
                            <button
                                key={`${seg.kind}-${seg.id}-${seg.startCol}`}
                                type="button"
                                data-bar-kind={seg.kind}
                                data-bar-id={seg.id}
                                onClick={(e) => {
                                    // 막대를 끌어 놓은 직후의 클릭은 이동이 이미 처리했다
                                    if (suppressClickRef.current) { suppressClickRef.current = false; return }
                                    if (quickCreate || edit || committedDrag) return
                                    setEdit({ kind: seg.kind, id: seg.id, ...popoverAt(sectionRef.current, e.clientX, e.clientY) })
                                }}
                                className={`absolute flex items-center truncate rounded px-1.5 text-[10px] ${
                                    seg.kind === "task" ? "text-white" : NEUTRAL_KIND_CLASS[seg.kind as Exclude<ScheduleItemKind, "task">]
                                } ${barDrag ? "" : "cursor-grab"} ${
                                    (barDrag ?? committedDrag)?.kind === seg.kind && (barDrag ?? committedDrag)?.id === seg.id
                                        ? "opacity-70 ring-2 ring-primary" : ""
                                }`}
                                style={{
                                    top: `${tops[si]}px`,
                                    left: `calc(${(seg.startCol / 7) * 100}% + 2px)`,
                                    width: `calc(${(seg.span / 7) * 100}% - 4px)`,
                                    height: `${heights[si] - 2}px`,
                                    ...(seg.kind === "task" && seg.color ? { backgroundColor: seg.color } : {}),
                                    borderTopLeftRadius: seg.continuesLeft ? 0 : undefined,
                                    borderBottomLeftRadius: seg.continuesLeft ? 0 : undefined,
                                    borderTopRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                                    borderBottomRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                                }}
                                title={seg.name}
                            >
                                <span className={`truncate ${seg.done ? "line-through opacity-60" : ""}`}>[{KIND_LABEL[seg.kind]}] {seg.name}{seg.openEnded && !seg.continuesRight ? " →" : ""}</span>
                                {resizable(seg.kind, seg.id) && !seg.continuesLeft ? (
                                    <span data-bar-handle="start" aria-hidden className="absolute inset-y-0 left-0 w-1.5 cursor-col-resize" />
                                ) : null}
                                {resizable(seg.kind, seg.id) && !seg.continuesRight ? (
                                    <span data-bar-handle="end" aria-hidden className="absolute inset-y-0 right-0 w-1.5 cursor-col-resize" />
                                ) : null}
                            </button>
                        ))}
                    </div>
                )
            })}
            {edit ? (() => {
                const target = entries.find((en) => en.kind === edit.kind && en.id === edit.id)
                if (!target) return null
                return (
                    <EntryEditPopover
                        x={edit.x}
                        y={edit.y}
                        kind={edit.kind}
                        initial={{ name: target.name, status: target.status, startDate: target.startDate, endDate: target.endDate }}
                        onSubmit={async (draft) => {
                            await onEntrySave(edit.kind, edit.id, draft)
                            setEdit(null)
                        }}
                        onDelete={() => {
                            setEdit(null)
                            onEntryDelete(edit.kind, edit.id)
                        }}
                        onClose={() => setEdit(null)}
                    />
                )
            })() : null}
            {quickCreate ? (
                <QuickTaskPopover
                    x={quickCreate.x}
                    y={quickCreate.y}
                    startIso={quickCreate.startIso}
                    endIso={quickCreate.endIso}
                    parentLabel={quickCreateParentLabel}
                    onSubmit={async (name) => {
                        await onQuickCreate(name, quickCreate.startIso, quickCreate.endIso, null)
                        setQuickCreate(null)
                    }}
                    onClose={() => setQuickCreate(null)}
                />
            ) : null}
        </section>
    )
}

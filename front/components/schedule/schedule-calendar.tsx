"use client"

import type React from "react"
import { useEffect, useRef, useState } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { calendarLayout, orderedRange, type CalendarWeek } from "@/lib/schedule-calendar"
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

interface DragState {
    anchor: string
    current: string
    /** 프로젝트 막대에서 시작한 드래그면 그 프로젝트, 빈 칸이면 null(무소속). */
    projectId: number | null
    startedOnBar: boolean
}

interface QuickCreateState {
    startIso: string
    endIso: string
    projectId: number | null
    x: number
    y: number
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
    const weeks = calendarLayout(entries, year, month)
    const today = todayIso()
    const [drag, setDrag] = useState<DragState | null>(null)
    const dragRef = useRef<DragState | null>(null)
    dragRef.current = drag
    const suppressClickRef = useRef(false)
    const [quickCreate, setQuickCreate] = useState<QuickCreateState | null>(null)
    const [edit, setEdit] = useState<EditState | null>(null)
    const sectionRef = useRef<HTMLElement>(null)

    // 마우스를 달력 밖에서 놓아도 드래그를 끝낸다 — 놓은 지점 옆에 빠른 생성 팝오버를 연다
    useEffect(() => {
        const finish = (e: MouseEvent) => {
            const d = dragRef.current
            if (!d) return
            setDrag(null)
            const moved = d.anchor !== d.current
            suppressClickRef.current = d.startedOnBar && moved
            // 막대 위에서 움직임 없이 놓으면 클릭(수정)으로 넘긴다
            if (d.startedOnBar && !moved) return
            const { start, end } = orderedRange(d.anchor, d.current)
            const rect = sectionRef.current?.getBoundingClientRect()
            const POPOVER_WIDTH = 288 // w-72
            const x = rect ? Math.min(Math.max(e.clientX - rect.left + 8, 8), rect.width - POPOVER_WIDTH - 8) : 8
            const y = rect ? Math.max(e.clientY - rect.top + 8, 8) : 8
            setQuickCreate({ startIso: start, endIso: end, projectId: d.projectId, x, y })
        }
        window.addEventListener("mouseup", finish)
        return () => window.removeEventListener("mouseup", finish)
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
        const bar = (e.target as HTMLElement).closest<HTMLElement>("[data-bar-kind]")
        const iso = isoAt(e, week)
        if (bar) {
            // 프로젝트 막대에서 시작하면 그 프로젝트 소속으로 생성, 다른 막대는 드래그 없음
            if (bar.dataset.barKind !== "project") return
            setDrag({ anchor: iso, current: iso, projectId: Number(bar.dataset.barId), startedOnBar: true })
        } else {
            e.preventDefault() // 텍스트 선택 방지
            setDrag({ anchor: iso, current: iso, projectId: null, startedOnBar: false })
        }
    }

    const updateDrag = (e: React.MouseEvent<HTMLDivElement>, week: CalendarWeek) => {
        if (!dragRef.current) return
        const iso = isoAt(e, week)
        if (iso !== dragRef.current.current) {
            setDrag({ ...dragRef.current, current: iso })
        }
    }

    const dragRange = drag
        ? orderedRange(drag.anchor, drag.current)
        : quickCreate
            ? { start: quickCreate.startIso, end: quickCreate.endIso }
            : null

    const quickCreateParentLabel = quickCreate?.projectId != null
        ? `「${entries.find((en) => en.kind === "project" && en.id === quickCreate.projectId)?.name ?? "?"}」 프로젝트 소속`
        : "어느 프로젝트에도 속하지 않는 바로 할 일"

    const move = (delta: number) => {
        const d = new Date(year, month - 1 + delta, 1)
        onMove(d.getFullYear(), d.getMonth() + 1)
    }

    return (
        <section ref={sectionRef} className="relative rounded-lg border">
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
                                    // 프로젝트 막대 드래그 직후의 클릭은 생성이 이미 처리했다
                                    if (suppressClickRef.current) { suppressClickRef.current = false; return }
                                    if (quickCreate || edit) return
                                    const rect = sectionRef.current?.getBoundingClientRect()
                                    const POPOVER_WIDTH = 288
                                    const x = rect ? Math.min(Math.max(e.clientX - rect.left + 8, 8), rect.width - POPOVER_WIDTH - 8) : 8
                                    const y = rect ? Math.max(e.clientY - rect.top + 8, 8) : 8
                                    setEdit({ kind: seg.kind, id: seg.id, x, y })
                                }}
                                className={`absolute flex items-center truncate rounded px-1.5 text-[10px] ${
                                    seg.kind === "task" ? "text-white" : NEUTRAL_KIND_CLASS[seg.kind as Exclude<ScheduleItemKind, "task">]
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
                        await onQuickCreate(name, quickCreate.startIso, quickCreate.endIso, quickCreate.projectId)
                        setQuickCreate(null)
                    }}
                    onClose={() => setQuickCreate(null)}
                />
            ) : null}
        </section>
    )
}

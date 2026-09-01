"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { calendarLayout, type CalendarWeek } from "@/lib/schedule-calendar"
import type { CalendarEntry, ScheduleItemKind } from "@/lib/schedule"

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"]
/** 할 일 막대 높이. 프로젝트/마일스톤은 얇게 그린다. */
const TASK_BAR = 20
const THIN_BAR = 14
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
        const h = seg.kind === "task" ? TASK_BAR : THIN_BAR
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

export default function ScheduleCalendar({ entries, year, month, onMove, onEntryClick }: {
    entries: CalendarEntry[]
    year: number
    month: number // 1~12
    onMove: (year: number, month: number) => void
    onEntryClick: (kind: ScheduleItemKind, id: number) => void
}) {
    const weeks = calendarLayout(entries, year, month)

    const move = (delta: number) => {
        const d = new Date(year, month - 1 + delta, 1)
        onMove(d.getFullYear(), d.getMonth() + 1)
    }

    return (
        <section className="rounded-lg border">
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
                    <div key={wi} className="relative grid grid-cols-7 border-b last:border-b-0"
                         style={{ minHeight: `${minHeight}px` }}>
                        {week.days.map((day, di) => (
                            <div key={di} className="border-r p-1 text-xs text-muted-foreground last:border-r-0">
                                {day ?? ""}
                            </div>
                        ))}
                        {week.segments.map((seg, si) => (
                            <button
                                key={`${seg.kind}-${seg.id}-${seg.startCol}`}
                                type="button"
                                onClick={() => onEntryClick(seg.kind, seg.id)}
                                className={`absolute flex items-center truncate rounded px-1.5 ${
                                    seg.kind === "task" ? "text-xs text-white" : `text-[10px] ${NEUTRAL_KIND_CLASS[seg.kind as Exclude<ScheduleItemKind, "task">]}`
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
                                <span className="truncate">[{KIND_LABEL[seg.kind]}] {seg.name}{seg.openEnded && !seg.continuesRight ? " →" : ""}</span>
                            </button>
                        ))}
                    </div>
                )
            })}
        </section>
    )
}

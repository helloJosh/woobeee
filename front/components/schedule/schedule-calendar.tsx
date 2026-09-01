"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { calendarLayout, type CalendarWeek } from "@/lib/schedule-calendar"
import type { CalendarEntry, ScheduleItemKind } from "@/lib/schedule"

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"]
const BAR_HEIGHT = 20
/** 같은 프로젝트 그룹 안에서는 살짝 붙인다. */
const ATTACH_GAP = 1
/** 프로젝트 그룹 사이 간격. */
const GROUP_GAP = 8
const WEEK_HEADER = 24
const WEEK_PAD = 6

/** lane → 세로 위치. 그룹 시작 lane 앞에만 GROUP_GAP, 나머지는 붙인다 (SCHEDULE-AC-30). */
function laneTops(week: CalendarWeek): number[] {
    const tops: number[] = []
    let y = WEEK_HEADER
    for (let i = 0; i < week.laneCount; i++) {
        if (i > 0) y += week.laneGroupStarts[i] ? GROUP_GAP : ATTACH_GAP
        tops.push(y)
        y += BAR_HEIGHT
    }
    return tops
}

const NEUTRAL_KIND_CLASS: Record<Exclude<ScheduleItemKind, "task">, string> = {
    project: "bg-slate-600 text-white font-semibold",
    milestone: "bg-slate-400 text-slate-950",
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
                const tops = laneTops(week)
                const minHeight = (week.laneCount > 0 ? tops[week.laneCount - 1] + BAR_HEIGHT : WEEK_HEADER) + WEEK_PAD
                return (
                    <div key={wi} className="relative grid grid-cols-7 border-b last:border-b-0"
                         style={{ minHeight: `${minHeight}px` }}>
                        {week.days.map((day, di) => (
                            <div key={di} className="border-r p-1 text-xs text-muted-foreground last:border-r-0">
                                {day ?? ""}
                            </div>
                        ))}
                        {week.segments.map((seg) => (
                            <button
                                key={`${seg.kind}-${seg.id}-${seg.startCol}`}
                                type="button"
                                onClick={() => onEntryClick(seg.kind, seg.id)}
                                className={`absolute flex items-center truncate rounded px-1.5 text-xs ${
                                    seg.kind === "task" ? "text-white" : NEUTRAL_KIND_CLASS[seg.kind as Exclude<ScheduleItemKind, "task">]
                                }`}
                                style={{
                                    top: `${tops[seg.lane]}px`,
                                    left: `calc(${(seg.startCol / 7) * 100}% + 2px)`,
                                    width: `calc(${(seg.span / 7) * 100}% - 4px)`,
                                    height: `${BAR_HEIGHT - 2}px`,
                                    ...(seg.kind === "task" && seg.color ? { backgroundColor: seg.color } : {}),
                                    borderTopLeftRadius: seg.continuesLeft ? 0 : undefined,
                                    borderBottomLeftRadius: seg.continuesLeft ? 0 : undefined,
                                    borderTopRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                                    borderBottomRightRadius: seg.continuesRight || seg.openEnded ? 0 : undefined,
                                }}
                                title={seg.name}
                            >
                                <span className="truncate">{seg.name}{seg.openEnded && !seg.continuesRight ? " →" : ""}</span>
                            </button>
                        ))}
                    </div>
                )
            })}
        </section>
    )
}

"use client"

import { useState } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { calendarLayout } from "@/lib/schedule-calendar"
import type { ScheduleTask } from "@/lib/schedule"

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"]
const LANE_HEIGHT = 22
const MAX_VISIBLE_LANES = 4

export default function ScheduleCalendar({ tasks, onTaskClick }: {
    tasks: ScheduleTask[]
    onTaskClick: (taskId: number) => void
}) {
    const now = new Date()
    const [year, setYear] = useState(now.getFullYear())
    const [month, setMonth] = useState(now.getMonth() + 1) // 1~12

    const weeks = calendarLayout(tasks, year, month)

    const move = (delta: number) => {
        const d = new Date(year, month - 1 + delta, 1)
        setYear(d.getFullYear())
        setMonth(d.getMonth() + 1)
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
            {weeks.map((week, wi) => (
                <div key={wi} className="relative grid grid-cols-7 border-b last:border-b-0"
                     style={{ minHeight: `${28 + MAX_VISIBLE_LANES * LANE_HEIGHT}px` }}>
                    {week.days.map((day, di) => (
                        <div key={di} className="border-r p-1 text-xs text-muted-foreground last:border-r-0">
                            {day ?? ""}
                        </div>
                    ))}
                    {week.segments.filter((s) => s.lane < MAX_VISIBLE_LANES).map((seg) => (
                        <button
                            key={`${seg.taskId}-${seg.startCol}`}
                            type="button"
                            onClick={() => onTaskClick(seg.taskId)}
                            className="absolute flex items-center truncate rounded px-1.5 text-xs text-white"
                            style={{
                                top: `${24 + seg.lane * LANE_HEIGHT}px`,
                                left: `calc(${(seg.startCol / 7) * 100}% + 2px)`,
                                width: `calc(${(seg.span / 7) * 100}% - 4px)`,
                                height: `${LANE_HEIGHT - 4}px`,
                                backgroundColor: seg.color,
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
            ))}
        </section>
    )
}

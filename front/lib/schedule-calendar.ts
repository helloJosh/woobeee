// front/lib/schedule-calendar.ts — 월 달력에 할 일 막대를 배치하는 순수 계산.
// 날짜는 전부 "YYYY-MM-DD" 문자열을 로컬 자정 기준으로 다룬다 (UTC 파싱 함정 회피).
import type { ScheduleTask } from "./schedule"

export interface CalendarSegment {
    taskId: number
    name: string
    color: string
    /** 같은 주 안에서 세로로 쌓이는 줄 번호 (0부터). */
    lane: number
    /** 주 안의 시작 칸 (0 = 일요일). */
    startCol: number
    /** 차지하는 칸 수 (1~7). */
    span: number
    /** 이 주 이전(왼쪽)에서 이어져 온다. */
    continuesLeft: boolean
    /** 이 주 이후(오른쪽)로 이어진다. */
    continuesRight: boolean
    /** 종료일 미정 — 월 말에서 화살표로 열어 둔다. */
    openEnded: boolean
}

export interface CalendarWeek {
    /** 일~토 7칸. 그 달의 날짜가 아니면 null. */
    days: (number | null)[]
    segments: CalendarSegment[]
}

function toLocalDate(iso: string): Date {
    const [y, m, d] = iso.split("-").map(Number)
    return new Date(y, m - 1, d)
}

function dayDiff(a: Date, b: Date): number {
    return Math.round((b.getTime() - a.getTime()) / 86_400_000)
}

/** month 는 1~12. 일요일 시작 주 단위 그리드에 할 일 막대를 배치한다. */
export function calendarLayout(tasks: ScheduleTask[], year: number, month: number): CalendarWeek[] {
    const first = new Date(year, month - 1, 1)
    const last = new Date(year, month, 0)
    const gridStart = new Date(year, month - 1, 1 - first.getDay())
    const weekCount = Math.ceil((first.getDay() + last.getDate()) / 7)

    const weeks: CalendarWeek[] = []
    for (let w = 0; w < weekCount; w++) {
        const days: (number | null)[] = []
        for (let c = 0; c < 7; c++) {
            const date = new Date(gridStart)
            date.setDate(gridStart.getDate() + w * 7 + c)
            days.push(date.getMonth() === month - 1 ? date.getDate() : null)
        }
        weeks.push({ days, segments: [] })
    }

    // 시작일 순으로 배치해야 lane 배정이 결정적이다
    const placeable = tasks
        .filter((t) => t.startDate !== null || t.endDate !== null)
        .map((t) => {
            const start = t.startDate ? toLocalDate(t.startDate) : toLocalDate(t.endDate!)
            const end = t.endDate ? toLocalDate(t.endDate) : last
            return { task: t, start, end, openEnded: t.endDate === null && t.startDate !== null }
        })
        .filter(({ start, end }) => start <= last && end >= first && end >= start)
        .sort((a, b) => a.start.getTime() - b.start.getTime() || a.task.id - b.task.id)

    // 주별 lane 점유 현황: lanes[w][lane] = 마지막으로 점유한 col
    const laneEnds: number[][] = weeks.map(() => [])

    for (const { task, start, end, openEnded } of placeable) {
        const clipStart = start < first ? first : start
        const clipEnd = end > last ? last : end

        let cursor = new Date(clipStart)
        while (cursor <= clipEnd) {
            const offset = dayDiff(gridStart, cursor)
            const w = Math.floor(offset / 7)
            const startCol = offset % 7
            const weekRemain = 7 - startCol
            const remainDays = dayDiff(cursor, clipEnd) + 1
            const span = Math.min(weekRemain, remainDays)

            // first-fit lane: 이 주에서 startCol 이전에 끝난 lane 재사용
            let lane = 0
            while (laneEnds[w][lane] !== undefined && laneEnds[w][lane] >= startCol) lane++
            laneEnds[w][lane] = startCol + span - 1

            weeks[w].segments.push({
                taskId: task.id,
                name: task.name,
                color: task.color,
                lane,
                startCol,
                span,
                continuesLeft: dayDiff(start, cursor) > 0,
                continuesRight: dayDiff(cursor, end) + 1 > span,
                openEnded,
            })

            cursor = new Date(cursor)
            cursor.setDate(cursor.getDate() + span)
        }
    }

    return weeks
}

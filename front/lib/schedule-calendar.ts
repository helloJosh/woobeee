// front/lib/schedule-calendar.ts — 월 달력에 일정 막대를 배치하는 순수 계산.
// 날짜는 전부 "YYYY-MM-DD" 문자열을 로컬 자정 기준으로 다룬다 (UTC 파싱 함정 회피).
import type { CalendarEntry, ScheduleItemKind } from "./schedule"

export interface CalendarSegment {
    kind: ScheduleItemKind
    id: number
    name: string
    /** 할 일 시작 시간 "HH:mm" — 라벨 앞에 붙인다. 없으면 null. */
    startTime: string | null
    /** 할 일 고유색. 프로젝트/마일스톤은 null — 컴포넌트가 중립 톤으로 그린다. */
    color: string | null
    /** 같은 주 안에서 세로로 쌓이는 줄 번호 (0부터). 같은 그룹 안에서 겹치지 않으면 줄을 재사용한다. */
    lane: number
    /** 주 안의 시작 칸 (0 = 일요일). */
    startCol: number
    /** 차지하는 칸 수 (1~7). */
    span: number
    /** 이 주 이전(왼쪽)에서 이어져 온다. */
    continuesLeft: boolean
    /** 이 주 이후(오른쪽)로 이어진다. */
    continuesRight: boolean
    /** 종료일 미정 — 시작일 하루만 표시되고 화살표(→)로 미정임을 알린다. */
    openEnded: boolean
    /** 완료 항목 — 트리처럼 취소선으로 그린다. */
    done: boolean
}

export interface CalendarDay {
    /** 그 칸의 일(day of month) — 앞뒤 달 날짜도 채운다. */
    date: number
    /** 이 달 소속 여부 — 앞뒤 달 칸은 흐리게 그린다. */
    inMonth: boolean
    /** 그 칸의 실제 날짜 "YYYY-MM-DD" — 클릭/드래그 생성이 쓴다 (SCHEDULE-AC-32). */
    iso: string
}

export interface CalendarWeek {
    /** 일~토 7칸. 앞뒤 달 날짜도 포함한다. */
    days: CalendarDay[]
    segments: CalendarSegment[]
    /** 이 주에 쌓인 줄 수 — 컴포넌트가 주 높이를 동적으로 계산한다 (막대를 숨기지 않는다). */
    laneCount: number
    /**
     * SCHEDULE-AC-30 — lane 별로 "새 프로젝트 그룹의 첫 줄"인지. 컴포넌트는 그룹 시작
     * lane 앞에만 간격을 주고, 같은 그룹 안의 막대는 붙여 그린다.
     */
    laneGroupStarts: boolean[]
}

function toLocalDate(iso: string): Date {
    const [y, m, d] = iso.split("-").map(Number)
    return new Date(y, m - 1, d)
}

function dayDiff(a: Date, b: Date): number {
    return Math.round((b.getTime() - a.getTime()) / 86_400_000)
}

function addDays(base: Date, days: number): Date {
    const d = new Date(base)
    d.setDate(base.getDate() + days)
    return d
}

function toIso(d: Date): string {
    const m = String(d.getMonth() + 1).padStart(2, "0")
    const day = String(d.getDate()).padStart(2, "0")
    return `${d.getFullYear()}-${m}-${day}`
}

/** 드래그 범위 — 어느 방향으로 끌든 [시작, 끝]으로 정렬한다 (SCHEDULE-AC-32). */
export function orderedRange(a: string, b: string): { start: string; end: string } {
    return a <= b ? { start: a, end: b } : { start: b, end: a }
}

/**
 * month 는 1~12. 일요일 시작 주 단위 그리드에 막대를 배치한다.
 * 그룹(프로젝트) 순서는 트리 순서 그대로이고, 그룹 안에서는 가로로 겹치지 않는 막대가
 * 같은 lane 을 재사용한다(first-fit) — 빈 자리가 있으면 위로 붙는다 (SCHEDULE-AC-30).
 * 막대는 이번 달 밖이라도 그리드에 보이는 앞뒤 달 칸까지 이어 그린다.
 */
export function calendarLayout(
    entries: CalendarEntry[],
    year: number,
    month: number,
): CalendarWeek[] {
    const first = new Date(year, month - 1, 1)
    const last = new Date(year, month, 0)
    const gridStart = new Date(year, month - 1, 1 - first.getDay())
    const weekCount = Math.ceil((first.getDay() + last.getDate()) / 7)

    const weeks: CalendarWeek[] = []
    for (let w = 0; w < weekCount; w++) {
        const days: CalendarDay[] = []
        for (let c = 0; c < 7; c++) {
            const date = addDays(gridStart, w * 7 + c)
            days.push({ date: date.getDate(), inMonth: date.getMonth() === month - 1, iso: toIso(date) })
        }
        weeks.push({ days, segments: [], laneCount: 0, laneGroupStarts: [] })
    }

    const gridEnd = addDays(gridStart, weekCount * 7 - 1)
    const placeable = entries
        .filter((e) => e.startDate !== null || e.endDate !== null)
        .map((e) => {
            if (e.endDate === null) {
                // 종료 미정 — 시작일 하루만 (SCHEDULE-AC-19)
                const start = toLocalDate(e.startDate!)
                return { entry: e, start, end: start, openEnded: true }
            }
            const start = e.startDate ? toLocalDate(e.startDate) : toLocalDate(e.endDate)
            return { entry: e, start, end: toLocalDate(e.endDate), openEnded: false }
        })
        // 그리드에 보이는 앞뒤 달 구간까지 배치 대상이다
        .filter(({ start, end }) => start <= gridEnd && end >= gridStart && end >= start)

    // 트리 순서 그대로 연속 구간을 그룹으로 묶는다 (같은 프로젝트는 연속으로 온다)
    type Placed = (typeof placeable)[number]
    const groups: Placed[][] = []
    let lastGroup: number | null | undefined = undefined
    for (const item of placeable) {
        if (groups.length === 0 || item.entry.projectId !== lastGroup) {
            groups.push([])
        }
        groups[groups.length - 1].push(item)
        lastGroup = item.entry.projectId
    }

    for (let w = 0; w < weekCount; w++) {
        const week = weeks[w]
        const weekStart = addDays(gridStart, w * 7)
        const weekEnd = addDays(weekStart, 6)
        let laneOffset = 0

        for (const group of groups) {
            // 이 주에 걸치는 세그먼트만 추려 시작 칸 순으로 first-fit 패킹한다
            const weekSegs: { item: Placed; startCol: number; endCol: number }[] = []
            for (const item of group) {
                const clipStart = item.start < gridStart ? gridStart : item.start
                const clipEnd = item.end > gridEnd ? gridEnd : item.end
                const segStart = clipStart > weekStart ? clipStart : weekStart
                const segEnd = clipEnd < weekEnd ? clipEnd : weekEnd
                if (segStart > segEnd) continue
                weekSegs.push({ item, startCol: dayDiff(weekStart, segStart), endCol: dayDiff(weekStart, segEnd) })
            }
            if (weekSegs.length === 0) continue

            weekSegs.sort((a, b) => a.startCol - b.startCol)
            const laneEnds: number[] = []
            for (const seg of weekSegs) {
                let lane = 0
                while (laneEnds[lane] !== undefined && laneEnds[lane] >= seg.startCol) lane++
                laneEnds[lane] = seg.endCol
                const { entry, start, end, openEnded } = seg.item
                const segStartDate = addDays(weekStart, seg.startCol)
                const segEndDate = addDays(weekStart, seg.endCol)
                week.segments.push({
                    kind: entry.kind,
                    id: entry.id,
                    name: entry.name,
                    startTime: entry.startTime ?? null,
                    color: entry.color,
                    lane: laneOffset + lane,
                    startCol: seg.startCol,
                    span: seg.endCol - seg.startCol + 1,
                    continuesLeft: start < segStartDate,
                    continuesRight: end > segEndDate,
                    openEnded,
                    done: entry.status === "DONE",
                })
            }
            for (let l = 0; l < laneEnds.length; l++) {
                week.laneGroupStarts.push(l === 0)
            }
            laneOffset += laneEnds.length
        }
        week.laneCount = laneOffset
    }

    return weeks
}

// ── SCHEDULE-AC-33 — 막대 드래그로 이동·리사이즈 ──────────────────────────────

export type BarDragMode = "move" | "resize-start" | "resize-end"

/** 막대 위에서 시작한 드래그. anchor 는 잡은 칸, current 는 지금 마우스가 있는 칸. */
export interface BarDrag {
    kind: ScheduleItemKind
    id: number
    mode: BarDragMode
    anchor: string
    current: string
}

export interface DateRange {
    startDate: string | null
    endDate: string | null
}

/** from → to 일수. 뒤로 가면 음수. */
export function daysBetween(fromIso: string, toIso: string): number {
    return dayDiff(toLocalDate(fromIso), toLocalDate(toIso))
}

function shiftIso(iso: string, days: number): string {
    return toIso(addDays(toLocalDate(iso), days))
}

/**
 * 드래그가 끝났을 때(또는 미리보기로) 항목이 가질 날짜 범위.
 * move 는 양끝을 같은 일수만큼 옮기고 null 은 null 로 둔다.
 * resize 는 한쪽 끝만 놓은 칸으로 바꾸되, 끝이 서로를 지나치면 하루짜리로 고정한다.
 */
export function draggedRange(range: DateRange, drag: BarDrag): DateRange {
    if (drag.mode === "move") {
        const delta = daysBetween(drag.anchor, drag.current)
        return {
            startDate: range.startDate === null ? null : shiftIso(range.startDate, delta),
            endDate: range.endDate === null ? null : shiftIso(range.endDate, delta),
        }
    }
    if (drag.mode === "resize-start") {
        const clamped = range.endDate !== null && drag.current > range.endDate ? range.endDate : drag.current
        return { startDate: clamped, endDate: range.endDate }
    }
    const clamped = range.startDate !== null && drag.current < range.startDate ? range.startDate : drag.current
    return { startDate: range.startDate, endDate: clamped }
}

/** 끌리는 항목만 새 날짜로 바꾼 entries — calendarLayout 에 넘기면 미리보기가 된다. 입력은 변형하지 않는다. */
export function applyBarDrag(entries: CalendarEntry[], drag: BarDrag | null): CalendarEntry[] {
    if (!drag) return entries
    return entries.map((e) =>
        e.kind === drag.kind && e.id === drag.id ? { ...e, ...draggedRange(e, drag) } : e,
    )
}

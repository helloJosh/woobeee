import { describe, expect, it } from "vitest"
import { calendarLayout } from "./schedule-calendar"
import type { CalendarEntry, ScheduleItemKind } from "./schedule"

function entry(partial: Partial<CalendarEntry> & { id: number }): CalendarEntry {
    return {
        kind: (partial.kind ?? "task") as ScheduleItemKind,
        projectId: 1, name: `e${partial.id}`, status: "NOT_STARTED",
        startDate: null, endDate: null, color: partial.kind === "task" || partial.kind === undefined ? "#ef4444" : null,
        ...partial,
    }
}

// 2026년 8월: 1일 = 토요일, 31일 = 월요일 → 6주 그리드
describe("calendarLayout (2026-08)", () => {
    it("그리드는 일요일 시작이고 앞뒤 달 날짜는 inMonth=false 로 채운다", () => {
        const weeks = calendarLayout([], 2026, 8)
        expect(weeks).toHaveLength(6)
        // 첫 주: 7/26(일)~7/31(금) + 8/1(토)
        expect(weeks[0].days.map((d) => d.date)).toEqual([26, 27, 28, 29, 30, 31, 1])
        expect(weeks[0].days.map((d) => d.inMonth)).toEqual([false, false, false, false, false, false, true])
        // 마지막 주: 8/30, 8/31 + 9/1~9/5
        expect(weeks[5].days.map((d) => d.date)).toEqual([30, 31, 1, 2, 3, 4, 5])
        expect(weeks[5].days.map((d) => d.inMonth)).toEqual([true, true, false, false, false, false, false])
    })

    it("주 안에 완전히 들어가는 항목은 한 세그먼트다", () => {
        // 8/3(월) ~ 8/5(수) → 둘째 주, col 1~3
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-08-03", endDate: "2026-08-05" })], 2026, 8)
        expect(weeks[1].segments).toHaveLength(1)
        expect(weeks[1].segments[0]).toMatchObject({
            id: 1, startCol: 1, span: 3,
            continuesLeft: false, continuesRight: false, openEnded: false,
        })
        expect(weeks[0].segments).toHaveLength(0)
        expect(weeks[2].segments).toHaveLength(0)
    })

    it("주를 넘는 항목은 주마다 잘리고 continues 플래그가 붙는다", () => {
        // 8/6(목) ~ 8/11(화): 둘째 주 col4~6 + 셋째 주 col0~2
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-08-06", endDate: "2026-08-11" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 4, span: 3, continuesLeft: false, continuesRight: true })
        expect(weeks[2].segments[0]).toMatchObject({ startCol: 0, span: 3, continuesLeft: true, continuesRight: false })
    })

    // SCHEDULE-AC-18 — 월 경계 잘림
    it("월 밖 구간은 잘리고 continuesLeft/Right 로 표시된다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-07-25", endDate: "2026-09-05" })], 2026, 8)
        expect(weeks[0].segments[0]).toMatchObject({ startCol: 6, span: 1, continuesLeft: true })
        expect(weeks[5].segments[0]).toMatchObject({ startCol: 0, span: 2, continuesRight: true })
    })

    // SCHEDULE-AC-19 — 종료 미정은 오늘 하루만 (세 종류 공통)
    it("종료 미정은 오늘 하루만 표시되고 openEnded 다", () => {
        // 오늘 = 2026-08-15(토) → 셋째 주(w2) col 6
        const today = new Date(2026, 7, 15)
        const weeks = calendarLayout([entry({ id: 1, kind: "project", startDate: "2026-08-01", endDate: null, color: null })], 2026, 8, today)
        const all = weeks.flatMap((w) => w.segments)
        expect(all).toHaveLength(1)
        expect(weeks[2].segments[0]).toMatchObject({ startCol: 6, span: 1, openEnded: true })
    })

    it("종료 미정은 오늘이 포함되지 않은 달에서는 표시되지 않는다", () => {
        const today = new Date(2026, 8, 10) // 2026-09-10
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-08-01", endDate: null })], 2026, 8, today)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    it("시작 없이 종료만 있으면 종료일 하루짜리다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: null, endDate: "2026-08-05" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 3, span: 1 })
    })

    it("날짜가 아예 없는 항목은 달력에 나오지 않는다", () => {
        const weeks = calendarLayout([entry({ id: 1 })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    it("월 범위와 무관한 항목은 나오지 않는다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-07-01", endDate: "2026-07-31" })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    // SCHEDULE-AC-30 — 그룹 lane: 트리 순서 그대로, 같은 프로젝트는 붙고 그룹 사이만 간격 표시
    it("같은 프로젝트 항목은 연속 lane 에 붙고, 새 그룹의 첫 lane 만 groupStart 다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, kind: "project", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-07", color: null }),
            entry({ id: 10, kind: "milestone", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-05", color: null }),
            entry({ id: 100, kind: "task", projectId: 1, startDate: "2026-08-04", endDate: "2026-08-06" }),
            entry({ id: 2, kind: "project", projectId: 2, startDate: "2026-08-05", endDate: "2026-08-07", color: null }),
        ], 2026, 8)

        const week = weeks[1]
        expect(week.laneCount).toBe(4)
        // 트리 순서 = lane 순서
        expect(week.segments.map((s) => `${s.kind}:${s.id}@${s.lane}`)).toEqual([
            "project:1@0", "milestone:10@1", "task:100@2", "project:2@3",
        ])
        // 그룹 시작: lane0(프로젝트1), lane3(프로젝트2)만
        expect(week.laneGroupStarts).toEqual([true, false, false, true])
    })

    it("어떤 주에 안 나오는 항목은 그 주의 lane 을 차지하지 않는다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, kind: "project", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-20", color: null }),
            entry({ id: 100, kind: "task", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-05" }),
            entry({ id: 2, kind: "project", projectId: 2, startDate: "2026-08-10", endDate: "2026-08-12", color: null }),
        ], 2026, 8)

        // 셋째 주(8/9~8/15): task 100 은 없고 project 1, project 2 만 → lane 0,1, 둘 다 그룹 시작
        const week = weeks[2]
        expect(week.segments.map((s) => `${s.id}@${s.lane}`)).toEqual(["1@0", "2@1"])
        expect(week.laneGroupStarts).toEqual([true, true])
    })

    it("kind 와 색이 세그먼트로 전달된다 — 할 일만 색을 가진다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, kind: "project", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-03", color: null }),
            entry({ id: 100, kind: "task", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-03", color: "#3b82f6" }),
        ], 2026, 8)
        const [p, t] = weeks[1].segments
        expect(p).toMatchObject({ kind: "project", color: null })
        expect(t).toMatchObject({ kind: "task", color: "#3b82f6" })
    })
})

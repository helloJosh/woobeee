import { describe, expect, it } from "vitest"
import { calendarLayout } from "./schedule-calendar"
import type { ScheduleTask } from "./schedule"

function task(partial: Partial<ScheduleTask> & { id: number }): ScheduleTask {
    return {
        milestoneId: null, name: `t${partial.id}`, status: "NOT_STARTED",
        startDate: null, endDate: null, color: "#ef4444",
        ...partial,
    }
}

// 2026년 8월: 1일 = 토요일, 31일 = 월요일 → 6주 그리드
describe("calendarLayout (2026-08)", () => {
    it("그리드는 일요일 시작이고 달 밖 칸은 null 이다", () => {
        const weeks = calendarLayout([], 2026, 8)
        expect(weeks).toHaveLength(6)
        expect(weeks[0].days).toEqual([null, null, null, null, null, null, 1])
        expect(weeks[5].days).toEqual([30, 31, null, null, null, null, null])
    })

    it("주 안에 완전히 들어가는 할 일은 한 세그먼트다", () => {
        // 8/3(월) ~ 8/5(수) → 둘째 주, col 1~3
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-03", endDate: "2026-08-05" })], 2026, 8)
        const segs = weeks[1].segments
        expect(segs).toHaveLength(1)
        expect(segs[0]).toMatchObject({
            taskId: 1, startCol: 1, span: 3,
            continuesLeft: false, continuesRight: false, openEnded: false,
        })
        // 다른 주에는 없다
        expect(weeks[0].segments).toHaveLength(0)
        expect(weeks[2].segments).toHaveLength(0)
    })

    it("주를 넘는 할 일은 주마다 잘리고 continues 플래그가 붙는다", () => {
        // 8/6(목) ~ 8/11(화): 둘째 주 col4~6 + 셋째 주 col0~2
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-06", endDate: "2026-08-11" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 4, span: 3, continuesLeft: false, continuesRight: true })
        expect(weeks[2].segments[0]).toMatchObject({ startCol: 0, span: 3, continuesLeft: true, continuesRight: false })
    })

    // SCHEDULE-AC-18 — 월 경계 잘림
    it("월 밖 구간은 잘리고 continuesLeft/Right 로 표시된다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-07-25", endDate: "2026-09-05" })], 2026, 8)
        // 첫 주: 8/1(토, col 6)부터, 왼쪽으로 이어짐
        expect(weeks[0].segments[0]).toMatchObject({ startCol: 6, span: 1, continuesLeft: true })
        // 마지막 주: 8/31(월, col 1)까지, 오른쪽으로 이어짐
        const last = weeks[5].segments[0]
        expect(last).toMatchObject({ startCol: 0, span: 2, continuesRight: true })
    })

    // SCHEDULE-AC-19 — 종료 미정
    it("종료 미정은 시작일부터 월 말까지 깔리고 openEnded 다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-08-30", endDate: null })], 2026, 8)
        const last = weeks[5].segments[0]
        expect(last).toMatchObject({ startCol: 0, span: 2, openEnded: true })
    })

    it("시작 없이 종료만 있으면 종료일 하루짜리다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: null, endDate: "2026-08-05" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 3, span: 1 })
    })

    it("날짜가 아예 없는 할 일은 달력에 나오지 않는다", () => {
        const weeks = calendarLayout([task({ id: 1 })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    it("겹치는 할 일은 서로 다른 lane 을 받고, 빈 lane 은 재사용된다", () => {
        const weeks = calendarLayout([
            task({ id: 1, startDate: "2026-08-03", endDate: "2026-08-07" }), // 월~금, col 1~5
            task({ id: 2, startDate: "2026-08-04", endDate: "2026-08-06" }), // 화~목, col 2~4 — 1과 겹침
            task({ id: 3, startDate: "2026-08-08", endDate: "2026-08-08" }), // 토, col 6 — 같은 주지만 1·2가 끝난 뒤
        ], 2026, 8)
        const lanes = new Map(weeks[1].segments.map((s) => [s.taskId, s.lane]))
        expect(lanes.get(1)).toBe(0)
        expect(lanes.get(2)).toBe(1)
        // 3번 차례에는 lane 0 이 col 5 에서 끝나 있으므로 재사용된다
        expect(lanes.get(3)).toBe(0)
    })

    it("월 범위와 무관한 할 일은 나오지 않는다", () => {
        const weeks = calendarLayout([task({ id: 1, startDate: "2026-07-01", endDate: "2026-07-31" })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })
})

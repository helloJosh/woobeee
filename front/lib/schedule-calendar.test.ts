import { describe, expect, it } from "vitest"
import { calendarLayout, orderedRange } from "./schedule-calendar"
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
        // 칸의 실제 날짜 — 앞뒤 달도 진짜 날짜다 (SCHEDULE-AC-32)
        expect(weeks[0].days[0].iso).toBe("2026-07-26")
        expect(weeks[0].days[6].iso).toBe("2026-08-01")
        expect(weeks[5].days[6].iso).toBe("2026-09-05")
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

    // SCHEDULE-AC-18 — 그리드 경계 잘림: 보이는 앞뒤 달 칸까지는 이어 그린다
    it("그리드에 보이는 앞뒤 달 칸까지 이어지고, 그리드 밖만 잘려 continues 로 표시된다", () => {
        // 8월 그리드는 7/26 ~ 9/5
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-07-20", endDate: "2026-09-09" })], 2026, 8)
        expect(weeks[0].segments[0]).toMatchObject({ startCol: 0, span: 7, continuesLeft: true })
        expect(weeks[5].segments[0]).toMatchObject({ startCol: 0, span: 7, continuesRight: true })
    })

    it("앞뒤 달 채운 칸 범위의 항목도 그려진다", () => {
        // 9/1(화) ~ 9/3(목) — 8월 뷰 마지막 주 col 2~4
        const weeks = calendarLayout([entry({ id: 1, kind: "milestone", projectId: 1, startDate: "2026-09-01", endDate: "2026-09-03", color: null })], 2026, 8)
        expect(weeks[5].segments[0]).toMatchObject({ startCol: 2, span: 3, continuesLeft: false, continuesRight: false })
    })

    // SCHEDULE-AC-19 — 종료 미정은 시작일 하루만 (세 종류 공통)
    it("종료 미정은 시작일 하루만 표시되고 openEnded 다", () => {
        // 8/1 = 토 → 첫 주 col 6
        const weeks = calendarLayout([entry({ id: 1, kind: "project", startDate: "2026-08-01", endDate: null, color: null })], 2026, 8)
        const all = weeks.flatMap((w) => w.segments)
        expect(all).toHaveLength(1)
        expect(weeks[0].segments[0]).toMatchObject({ startCol: 6, span: 1, openEnded: true })
    })

    it("종료 미정은 시작일이 그리드 밖이면 표시되지 않는다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-06-10", endDate: null })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    // 완료 항목은 달력 막대도 취소선 대상이다
    it("완료 항목의 세그먼트는 done 플래그를 가진다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, status: "DONE", startDate: "2026-08-03", endDate: "2026-08-04" }),
            entry({ id: 2, status: "IN_PROGRESS", startDate: "2026-08-05", endDate: "2026-08-06" }),
        ], 2026, 8)
        const byId = new Map(weeks[1].segments.map((s) => [s.id, s.done]))
        expect(byId.get(1)).toBe(true)
        expect(byId.get(2)).toBe(false)
    })

    it("시작 없이 종료만 있으면 종료일 하루짜리다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: null, endDate: "2026-08-05" })], 2026, 8)
        expect(weeks[1].segments[0]).toMatchObject({ startCol: 3, span: 1 })
    })

    it("날짜가 아예 없는 항목은 달력에 나오지 않는다", () => {
        const weeks = calendarLayout([entry({ id: 1 })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    it("그리드 범위(7/26~9/5)와 무관한 항목은 나오지 않는다", () => {
        const weeks = calendarLayout([entry({ id: 1, startDate: "2026-06-01", endDate: "2026-06-30" })], 2026, 8)
        expect(weeks.every((w) => w.segments.length === 0)).toBe(true)
    })

    // 사용자 보고 재현 — 겹치지 않는 두 할 일이 줄을 낭비하지 않는다
    it("같은 그룹에서 가로로 겹치지 않으면 같은 lane 을 재사용한다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, startDate: "2026-08-03", endDate: "2026-08-04" }),
            entry({ id: 2, startDate: "2026-08-05", endDate: "2026-08-06" }),
        ], 2026, 8)
        const lanes = new Map(weeks[1].segments.map((s) => [s.id, s.lane]))
        expect(lanes.get(1)).toBe(0)
        expect(lanes.get(2)).toBe(0)
        expect(weeks[1].laneCount).toBe(1)
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

    // SCHEDULE-AC-31 — 무소속(projectId null) 막대는 자기 그룹으로 묶인다
    it("무소속 그룹은 별도 그룹으로 시작 플래그를 받는다", () => {
        const weeks = calendarLayout([
            entry({ id: 1, kind: "project", projectId: 1, startDate: "2026-08-03", endDate: "2026-08-05", color: null }),
            entry({ id: 200, projectId: null, startDate: "2026-08-03", endDate: "2026-08-04" }),
            entry({ id: 201, projectId: null, startDate: "2026-08-04", endDate: "2026-08-05" }),
        ], 2026, 8)
        const week = weeks[1]
        expect(week.laneGroupStarts).toEqual([true, true, false])
    })
})

// SCHEDULE-AC-32 — 드래그 방향과 무관하게 정렬된 범위
describe("orderedRange", () => {
    it("정방향은 그대로, 역방향은 뒤집는다", () => {
        expect(orderedRange("2026-08-03", "2026-08-07")).toEqual({ start: "2026-08-03", end: "2026-08-07" })
        expect(orderedRange("2026-08-07", "2026-08-03")).toEqual({ start: "2026-08-03", end: "2026-08-07" })
        expect(orderedRange("2026-08-05", "2026-08-05")).toEqual({ start: "2026-08-05", end: "2026-08-05" })
    })
})

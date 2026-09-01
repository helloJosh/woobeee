import { describe, expect, it } from "vitest"
import {
    SCHEDULE_COLORS,
    MAX_MILESTONE_DEPTH,
    collectTasks,
    filterTree,
    formatDateRange,
    isValidDateRange,
    isValidHexColor,
    isValidSlackWebhookUrl,
    nextStatus,
    todayIso,
    type ScheduleTree,
} from "./schedule"

const tree: ScheduleTree = {
    projects: [
        {
            id: 1, name: "DM", status: "IN_PROGRESS", startDate: "2026-08-20", endDate: "2026-09-04",
            tasks: [
                { id: 100, milestoneId: null, name: "직속 완료", status: "DONE", startDate: null, endDate: null, color: "#ef4444" },
            ],
            milestones: [
                {
                    id: 10, name: "POC", status: "IN_PROGRESS", startDate: null, endDate: null,
                    tasks: [
                        { id: 101, milestoneId: 10, name: "진행중 일", status: "IN_PROGRESS", startDate: "2026-08-26", endDate: "2026-09-01", color: "#3b82f6" },
                    ],
                    milestones: [
                        {
                            id: 11, name: "하위", status: "NOT_STARTED", startDate: null, endDate: null,
                            tasks: [], milestones: [],
                        },
                    ],
                },
            ],
        },
        {
            id: 2, name: "POSCO", status: "DONE", startDate: null, endDate: null,
            tasks: [], milestones: [],
        },
    ],
}

describe("filterTree", () => {
    it("ALL 은 원본 구조를 그대로 유지한다", () => {
        const filtered = filterTree(tree, "ALL")
        expect(filtered.projects).toHaveLength(2)
        expect(filtered.projects[0].dimmed).toBe(false)
    })

    // SCHEDULE-AC-17
    it("상태가 일치하는 노드와 조상 체인만 남기고, 조상은 dimmed 처리한다", () => {
        const filtered = filterTree(tree, "IN_PROGRESS")

        // DONE 뿐인 POSCO 는 사라진다
        expect(filtered.projects.map((p) => p.id)).toEqual([1])
        const dm = filtered.projects[0]
        // DM 자신이 IN_PROGRESS 라 dimmed 아님
        expect(dm.dimmed).toBe(false)
        // 직속 DONE 할 일은 사라진다
        expect(dm.tasks).toHaveLength(0)
        // POC 는 자신이 일치, 하위 NOT_STARTED 마일스톤은 사라진다
        expect(dm.milestones).toHaveLength(1)
        expect(dm.milestones[0].milestones).toHaveLength(0)
        expect(dm.milestones[0].tasks.map((t) => t.id)).toEqual([101])
    })

    it("자신은 불일치지만 일치하는 자손이 있으면 dimmed 로 남는다", () => {
        const filtered = filterTree(tree, "NOT_STARTED")

        // DM(IN_PROGRESS) 은 NOT_STARTED 자손(마일스톤 11) 때문에 dimmed 로 남는다
        expect(filtered.projects.map((p) => p.id)).toEqual([1])
        expect(filtered.projects[0].dimmed).toBe(true)
        expect(filtered.projects[0].milestones[0].dimmed).toBe(true)
        expect(filtered.projects[0].milestones[0].milestones[0].dimmed).toBe(false)
    })
})

describe("collectTasks", () => {
    it("직속과 중첩 마일스톤의 할 일을 전부 평탄화한다", () => {
        expect(collectTasks(tree).map((t) => t.id).sort()).toEqual([100, 101])
    })
})

describe("formatDateRange", () => {
    const today = new Date(2026, 8, 1) // 2026-09-01

    // SCHEDULE-AC-20
    it("올해 날짜는 연도를 생략한다", () => {
        expect(formatDateRange("2026-08-20", "2026-08-31", today)).toBe("08.20 ~ 08.31")
    })

    it("다른 해는 YY. 접두를 붙인다", () => {
        expect(formatDateRange("2025-12-31", "2026-01-02", today)).toBe("25.12.31 ~ 01.02")
    })

    it("종료 미정은 미정으로 표기한다", () => {
        expect(formatDateRange("2026-08-24", null, today)).toBe("08.24 ~ 미정")
    })

    it("시작 없이 종료만 있으면 종료만 보여준다", () => {
        expect(formatDateRange(null, "2026-08-31", today)).toBe("~ 08.31")
    })

    it("둘 다 없으면 빈 문자열", () => {
        expect(formatDateRange(null, null, today)).toBe("")
    })
})

describe("palette", () => {
    it("팔레트는 12색이고 전부 유효한 hex 다", () => {
        expect(SCHEDULE_COLORS).toHaveLength(12)
        for (const c of SCHEDULE_COLORS) expect(isValidHexColor(c)).toBe(true)
    })

    it("isValidHexColor 는 #RRGGBB 만 허용한다", () => {
        expect(isValidHexColor("#ef4444")).toBe(true)
        expect(isValidHexColor("#EF4444")).toBe(true)
        expect(isValidHexColor("red")).toBe(false)
        expect(isValidHexColor("#fff")).toBe(false)
        expect(isValidHexColor("#ef44441")).toBe(false)
    })

    it("깊이 상한은 서버와 같은 5", () => {
        expect(MAX_MILESTONE_DEPTH).toBe(5)
    })
})

describe("isValidDateRange", () => {
    it("둘 다 없으면 유효하다", () => {
        expect(isValidDateRange(null, null)).toBe(true)
    })

    it("시작만 있으면 유효하다", () => {
        expect(isValidDateRange("2026-08-20", null)).toBe(true)
    })

    it("종료만 있으면 유효하다", () => {
        expect(isValidDateRange(null, "2026-08-20")).toBe(true)
    })

    it("종료가 시작과 같으면 유효하다", () => {
        expect(isValidDateRange("2026-08-20", "2026-08-20")).toBe(true)
    })

    it("종료가 시작보다 늦으면 유효하다", () => {
        expect(isValidDateRange("2026-08-20", "2026-08-21")).toBe(true)
    })

    it("종료가 시작보다 빠르면 무효하다", () => {
        expect(isValidDateRange("2026-08-20", "2026-08-19")).toBe(false)
    })
})

// SCHEDULE-AC-29
describe("nextStatus", () => {
    it("시작전 → 진행중 → 완료 → 시작전으로 순환한다", () => {
        expect(nextStatus("NOT_STARTED")).toBe("IN_PROGRESS")
        expect(nextStatus("IN_PROGRESS")).toBe("DONE")
        expect(nextStatus("DONE")).toBe("NOT_STARTED")
    })
})

// SCHEDULE-AC-24
describe("isValidSlackWebhookUrl", () => {
    it("hooks.slack.com 으로 시작하는 https URL만 허용한다", () => {
        expect(isValidSlackWebhookUrl("https://hooks.slack.com/services/T0/B0/xxx")).toBe(true)
        expect(isValidSlackWebhookUrl("https://example.com/hook")).toBe(false)
        expect(isValidSlackWebhookUrl("http://hooks.slack.com/services/T0")).toBe(false)
        expect(isValidSlackWebhookUrl("")).toBe(false)
    })
})

// SCHEDULE-AC-23
describe("todayIso", () => {
    it("로컬 기준 YYYY-MM-DD 로 만든다", () => {
        expect(todayIso(new Date(2026, 8, 1))).toBe("2026-09-01")
    })

    it("한 자리 월·일은 0 을 채운다", () => {
        expect(todayIso(new Date(2026, 0, 5))).toBe("2026-01-05")
    })
})

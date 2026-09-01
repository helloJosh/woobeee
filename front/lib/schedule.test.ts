import { describe, expect, it } from "vitest"
import {
    SCHEDULE_COLORS,
    MAX_MILESTONE_DEPTH,
    applyStatus,
    collectCalendarEntries,
    collectTasks,
    findMilestone,
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
    tasks: [
        { id: 200, milestoneId: null, name: "무소속 진행중", status: "IN_PROGRESS", startDate: null, endDate: null, color: "#f97316" },
    ],
}

describe("filterTree", () => {
    it("ALL 은 원본 구조를 그대로 유지한다", () => {
        const filtered = filterTree(tree, "ALL")
        expect(filtered.projects).toHaveLength(2)
        expect(filtered.projects[0].dimmed).toBe(false)
        expect(filtered.tasks.map((t) => t.id)).toEqual([200])
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
        // 무소속도 자기 상태로 필터된다 (SCHEDULE-AC-31)
        expect(filtered.tasks.map((t) => t.id)).toEqual([200])
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
    it("직속·중첩·무소속 할 일을 전부 평탄화한다", () => {
        expect(collectTasks(tree).map((t) => t.id).sort()).toEqual([100, 101, 200])
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

    it("둘 다 없으면 '일정 미정'으로 표기한다", () => {
        expect(formatDateRange(null, null, today)).toBe("일정 미정")
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

// SCHEDULE-AC-30 — 달력 평탄화: 트리 순서, 같은 프로젝트 연속, kind/color 구분
describe("collectCalendarEntries", () => {
    it("프로젝트 → 직속 할 일 → 마일스톤(재귀) 순서로, 같은 프로젝트가 연속으로 나온다", () => {
        const entries = collectCalendarEntries(tree)
        expect(entries.map((e) => `${e.kind}:${e.id}`)).toEqual([
            "project:1", "task:100", "milestone:10", "task:101", "milestone:11",
            "project:2", "task:200",
        ])
        // 무소속은 맨 마지막 그룹이고 projectId 가 null 이다 (SCHEDULE-AC-31)
        expect(entries[entries.length - 1].projectId).toBeNull()
    })

    it("할 일만 고유색을 가지고 프로젝트/마일스톤은 null 이다", () => {
        const entries = collectCalendarEntries(tree)
        expect(entries.find((e) => e.kind === "task" && e.id === 101)?.color).toBe("#3b82f6")
        expect(entries.find((e) => e.kind === "project")?.color).toBeNull()
        expect(entries.find((e) => e.kind === "milestone")?.color).toBeNull()
    })
})

describe("findMilestone", () => {
    it("중첩 마일스톤을 프로젝트 id와 함께 찾는다", () => {
        const hit = findMilestone(tree, 11)
        expect(hit?.projectId).toBe(1)
        expect(hit?.milestone.name).toBe("하위")
    })

    it("없는 id 는 null", () => {
        expect(findMilestone(tree, 999)).toBeNull()
    })
})

// SCHEDULE-AC-29 — 옵티미스틱 반영: 해당 노드의 상태만 바뀌고 나머지는 그대로
describe("applyStatus", () => {
    it("프로젝트 상태만 바꾼다", () => {
        const next = applyStatus(tree, "project", 2, "IN_PROGRESS")
        expect(next.projects[1].status).toBe("IN_PROGRESS")
        // 나머지는 값이 그대로다
        expect(next.projects[0].status).toBe(tree.projects[0].status)
        expect(next.projects[0].milestones[0].tasks[0].status).toBe("IN_PROGRESS")
    })

    it("중첩 마일스톤 상태만 바꾼다", () => {
        const next = applyStatus(tree, "milestone", 11, "DONE")
        expect(next.projects[0].milestones[0].milestones[0].status).toBe("DONE")
        expect(next.projects[0].milestones[0].status).toBe("IN_PROGRESS")
    })

    it("중첩 할 일 상태만 바꾼다", () => {
        const next = applyStatus(tree, "task", 101, "DONE")
        expect(next.projects[0].milestones[0].tasks[0].status).toBe("DONE")
        expect(next.projects[0].tasks[0].status).toBe(tree.projects[0].tasks[0].status)
    })

    it("없는 id 는 아무것도 바꾸지 않는다", () => {
        const next = applyStatus(tree, "task", 999, "DONE")
        expect(next).toEqual(tree)
    })

    it("무소속 할 일 상태도 바꾼다", () => {
        const next = applyStatus(tree, "task", 200, "DONE")
        expect(next.tasks[0].status).toBe("DONE")
        expect(next.projects[0].tasks[0].status).toBe(tree.projects[0].tasks[0].status)
    })

    it("원본 트리는 변형되지 않는다", () => {
        const before = JSON.stringify(tree)
        applyStatus(tree, "project", 1, "DONE")
        expect(JSON.stringify(tree)).toBe(before)
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

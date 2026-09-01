// front/lib/schedule.ts — 일정 탭의 React-free 판단 로직.
// 컴포넌트에는 판단을 두지 않는다 (vitest 가 node 환경이라 컴포넌트는 검증 밖이다).

export type ScheduleStatus = "NOT_STARTED" | "IN_PROGRESS" | "DONE"
export type StatusFilter = ScheduleStatus | "ALL"

export interface ScheduleTask {
    id: number
    milestoneId: number | null
    name: string
    status: ScheduleStatus
    startDate: string | null // "YYYY-MM-DD"
    endDate: string | null
    color: string
}

export interface ScheduleMilestone {
    id: number
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    milestones: ScheduleMilestone[]
    tasks: ScheduleTask[]
}

export interface ScheduleProject {
    id: number
    name: string
    status: ScheduleStatus
    startDate: string | null
    endDate: string | null
    milestones: ScheduleMilestone[]
    tasks: ScheduleTask[]
}

export interface ScheduleTree {
    projects: ScheduleProject[]
}

// 서버 ScheduleColors.PALETTE 와 동일해야 한다 (스펙 §4 가 단일 출처).
export const SCHEDULE_COLORS = [
    "#ef4444", "#f97316", "#f59e0b", "#84cc16", "#22c55e", "#14b8a6",
    "#06b6d4", "#3b82f6", "#6366f1", "#8b5cf6", "#d946ef", "#ec4899",
] as const

export const MAX_MILESTONE_DEPTH = 5

export const STATUS_LABELS: Record<ScheduleStatus, string> = {
    NOT_STARTED: "시작전",
    IN_PROGRESS: "진행중",
    DONE: "완료",
}

export function isValidHexColor(value: string): boolean {
    return /^#[0-9a-fA-F]{6}$/.test(value)
}

/** 둘 다 있을 때만 순서를 본다 — 한쪽이 비면(미정) 항상 유효. */
export function isValidDateRange(start: string | null, end: string | null): boolean {
    if (!start || !end) return true
    return end >= start
}

export type ScheduleItemKind = "project" | "milestone" | "task"

/**
 * SCHEDULE-AC-29 — 옵티미스틱 반영: 트리에서 해당 노드의 상태만 바꾼 새 트리를 돌려준다.
 * 원본은 변형하지 않는다. 저장 실패 시 호출부가 재조회로 원복한다.
 */
export function applyStatus(
    tree: ScheduleTree,
    kind: ScheduleItemKind,
    id: number,
    status: ScheduleStatus,
): ScheduleTree {
    const mapTask = (t: ScheduleTask): ScheduleTask =>
        kind === "task" && t.id === id ? { ...t, status } : t
    const mapMilestone = (m: ScheduleMilestone): ScheduleMilestone => ({
        ...m,
        status: kind === "milestone" && m.id === id ? status : m.status,
        tasks: m.tasks.map(mapTask),
        milestones: m.milestones.map(mapMilestone),
    })
    return {
        projects: tree.projects.map((p) => ({
            ...p,
            status: kind === "project" && p.id === id ? status : p.status,
            tasks: p.tasks.map(mapTask),
            milestones: p.milestones.map(mapMilestone),
        })),
    }
}

/** SCHEDULE-AC-29 — 배지 클릭 순환: 시작전 → 진행중 → 완료 → 시작전. */
export function nextStatus(status: ScheduleStatus): ScheduleStatus {
    if (status === "NOT_STARTED") return "IN_PROGRESS"
    if (status === "IN_PROGRESS") return "DONE"
    return "NOT_STARTED"
}

/** SCHEDULE-AC-24 — Slack Incoming Webhook 만 허용한다 (서버와 같은 규칙). */
export function isValidSlackWebhookUrl(url: string): boolean {
    return url.startsWith("https://hooks.slack.com/")
}

/** 로컬 기준 오늘을 "YYYY-MM-DD"로 — 할 일 생성 시작일 기본값 (SCHEDULE-AC-23). */
export function todayIso(now: Date = new Date()): string {
    const y = now.getFullYear()
    const m = String(now.getMonth() + 1).padStart(2, "0")
    const d = String(now.getDate()).padStart(2, "0")
    return `${y}-${m}-${d}`
}

export interface FilteredMilestone extends Omit<ScheduleMilestone, "milestones"> {
    dimmed: boolean
    milestones: FilteredMilestone[]
}

export interface FilteredProject extends Omit<ScheduleProject, "milestones"> {
    dimmed: boolean
    milestones: FilteredMilestone[]
}

export interface FilteredTree {
    projects: FilteredProject[]
}

function filterMilestone(m: ScheduleMilestone, filter: StatusFilter): FilteredMilestone | null {
    const children = m.milestones
        .map((child) => filterMilestone(child, filter))
        .filter((child): child is FilteredMilestone => child !== null)
    const tasks = filter === "ALL" ? m.tasks : m.tasks.filter((t) => t.status === filter)
    const selfMatches = filter === "ALL" || m.status === filter

    if (!selfMatches && children.length === 0 && tasks.length === 0) return null
    return { ...m, milestones: children, tasks, dimmed: !selfMatches }
}

/** 자기 상태가 일치하는 노드 + 그 조상 체인만 남긴다. 조상은 dimmed. (SCHEDULE-AC-17) */
export function filterTree(tree: ScheduleTree, filter: StatusFilter): FilteredTree {
    const projects = tree.projects
        .map((p) => {
            const milestones = p.milestones
                .map((m) => filterMilestone(m, filter))
                .filter((m): m is FilteredMilestone => m !== null)
            const tasks = filter === "ALL" ? p.tasks : p.tasks.filter((t) => t.status === filter)
            const selfMatches = filter === "ALL" || p.status === filter

            if (!selfMatches && milestones.length === 0 && tasks.length === 0) return null
            return { ...p, milestones, tasks, dimmed: !selfMatches }
        })
        .filter((p): p is FilteredProject => p !== null)
    return { projects }
}

/** 달력용 평탄화 — 직속·중첩 가리지 않고 모든 할 일. */
export function collectTasks(tree: ScheduleTree): ScheduleTask[] {
    const out: ScheduleTask[] = []
    const walk = (milestones: ScheduleMilestone[]) => {
        for (const m of milestones) {
            out.push(...m.tasks)
            walk(m.milestones)
        }
    }
    for (const p of tree.projects) {
        out.push(...p.tasks)
        walk(p.milestones)
    }
    return out
}

function formatOne(iso: string, todayYear: number): string {
    const [y, m, d] = iso.split("-")
    return Number(y) === todayYear ? `${m}.${d}` : `${y.slice(2)}.${m}.${d}`
}

/** SCHEDULE-AC-20 — "08.20 ~ 미정" 표기. 올해는 연도 생략, 다른 해는 YY. 접두. */
export function formatDateRange(
    start: string | null,
    end: string | null,
    today: Date = new Date(),
): string {
    const year = today.getFullYear()
    if (!start && !end) return ""
    if (start && !end) return `${formatOne(start, year)} ~ 미정`
    if (!start && end) return `~ ${formatOne(end, year)}`
    return `${formatOne(start!, year)} ~ ${formatOne(end!, year)}`
}

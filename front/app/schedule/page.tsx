"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { Bell, Plus } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import ScheduleTree from "@/components/schedule/schedule-tree"
import ScheduleCalendar from "@/components/schedule/schedule-calendar"
import ScheduleItemDialog, { type ItemDraft, type ItemKind } from "@/components/schedule/schedule-item-dialog"
import NotificationDialog from "@/components/schedule/notification-dialog"
import { useAuth } from "@/hooks/use-auth"
import { buildAuthHref } from "@/lib/auth-redirect"
import { scheduleAPI } from "@/lib/api"
import {
    applyStatus, collectCalendarEntries, collectTasks, filterTree, findMilestone, nextStatus,
    STATUS_LABELS, todayIso,
    type FilteredMilestone, type FilteredProject, type ScheduleItemKind, type ScheduleStatus,
    type ScheduleTask, type ScheduleTree as Tree, type StatusFilter,
} from "@/lib/schedule"

const SCHEDULE_PATH = "/schedule"
const EMPTY_DRAFT: ItemDraft = { name: "", status: "NOT_STARTED", startDate: null, endDate: null }

/** 어떤 다이얼로그가 열려 있는가. null 이면 닫힘. */
type DialogState =
    | { kind: "project"; mode: "create" }
    | { kind: "project"; mode: "edit"; id: number }
    | { kind: "milestone"; mode: "create"; projectId: number; parentId: number | null }
    | { kind: "milestone"; mode: "edit"; projectId: number; id: number }
    | { kind: "task"; mode: "create"; projectId: number | null; milestoneId: number | null }
    | { kind: "task"; mode: "edit"; projectId: number | null; id: number }
    | null

export default function SchedulePage() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()
    const [tree, setTree] = useState<Tree | null>(null)
    const [treeState, setTreeState] = useState<"loading" | "ready" | "failed">("loading")
    const [filter, setFilter] = useState<StatusFilter>("ALL")
    const [dialog, setDialog] = useState<DialogState>(null)
    const [dialogInitial, setDialogInitial] = useState<ItemDraft>(EMPTY_DRAFT)
    const [dialogContext, setDialogContext] = useState<string | null>(null)
    const [notifOpen, setNotifOpen] = useState(false)
    const [calYear, setCalYear] = useState(() => new Date().getFullYear())
    const [calMonth, setCalMonth] = useState(() => new Date().getMonth() + 1)

    // 첫 로드에만 스켈레톤을 보여준다 — 이후 갱신(배지 클릭·저장·삭제)은 화면을 유지한 채
    // 조용히 바꿔치기해서 전체가 깜박이지 않게 한다. 갱신 실패는 apiRequest 가 alert 로 알린다.
    const hasLoadedOnce = useRef(false)
    const fetchTree = useCallback(async () => {
        try {
            if (!hasLoadedOnce.current) setTreeState("loading")
            setTree(await scheduleAPI.getTree())
            hasLoadedOnce.current = true
            setTreeState("ready")
        } catch {
            if (!hasLoadedOnce.current) setTreeState("failed")
        }
    }, [])

    useEffect(() => {
        if (loading) return
        if (!isAuthenticated) {
            router.replace(buildAuthHref("/login", SCHEDULE_PATH))
            return
        }
        void fetchTree()
    }, [loading, isAuthenticated, router, fetchTree])

    if (loading || !isAuthenticated) {
        return <div className="mx-auto max-w-4xl p-6"><Skeleton className="h-40 w-full" /></div>
    }

    const filtered = tree ? filterTree(tree, filter) : null
    // 달력은 프로젝트·마일스톤·할 일 전부 — 상태 필터는 각 막대의 자기 상태로 적용 (SCHEDULE-AC-30)
    const allEntries = tree ? collectCalendarEntries(tree) : []
    const calendarEntries = filter === "ALL" ? allEntries : allEntries.filter((e) => e.status === filter)

    const findTask = (taskId: number): { projectId: number | null; task: ScheduleTask } | null => {
        if (!tree) return null
        for (const p of tree.projects) {
            const hit = collectTasks({ projects: [p], tasks: [] }).find((t) => t.id === taskId)
            if (hit) return { projectId: p.id, task: hit }
        }
        const standalone = tree.tasks.find((t) => t.id === taskId)
        return standalone ? { projectId: null, task: standalone } : null
    }

    const findMilestoneParentId = (milestoneId: number): number | null => {
        if (!tree) return null
        for (const p of tree.projects) {
            const list: { id: number; parentId: number | null }[] = []
            const visit = (ms: typeof p.milestones, parentId: number | null) => {
                for (const m of ms) { list.push({ id: m.id, parentId }); visit(m.milestones, m.id) }
            }
            visit(p.milestones, null)
            const hit = list.find((m) => m.id === milestoneId)
            if (hit) return hit.parentId
        }
        return null
    }

    // SCHEDULE-AC-29 — 배지 클릭: 해당 노드의 상태만 옵티미스틱으로 즉시 바꾸고(재조회 없음 →
    // 나머지는 다시 그리지 않는다), 저장은 백그라운드로. 실패했을 때만 재조회로 원복한다.
    const cycleStatus = async (kind: ScheduleItemKind, id: number, save: () => Promise<unknown>, to: ScheduleStatus) => {
        setTree((prev) => (prev ? applyStatus(prev, kind, id, to) : prev))
        try {
            await save()
        } catch {
            await fetchTree()
        }
    }
    const cycleProject = (p: FilteredProject) => {
        const to = nextStatus(p.status)
        return cycleStatus("project", p.id,
            () => scheduleAPI.updateProject(p.id, { name: p.name, status: to, startDate: p.startDate, endDate: p.endDate }), to)
    }
    const cycleMilestone = (m: FilteredMilestone) => {
        const to = nextStatus(m.status)
        return cycleStatus("milestone", m.id,
            () => scheduleAPI.updateMilestone(m.id, { name: m.name, status: to, startDate: m.startDate, endDate: m.endDate, parentId: findMilestoneParentId(m.id) }), to)
    }
    const cycleTask = (task: ScheduleTask) => {
        const to = nextStatus(task.status)
        return cycleStatus("task", task.id,
            () => scheduleAPI.updateTask(task.id, { name: task.name, status: to, startDate: task.startDate, endDate: task.endDate, milestoneId: task.milestoneId, color: task.color }), to)
    }

    /** 생성 다이얼로그의 "어디에 만드는지" 안내 문구. */
    const parentLabel = (projectId: number | null, milestoneId: number | null): string => {
        if (projectId === null) return "어느 프로젝트에도 속하지 않는 바로 할 일"
        if (milestoneId !== null && tree) {
            const hit = findMilestone(tree, milestoneId)
            if (hit) return `「${hit.milestone.name}」 마일스톤 아래에 추가`
        }
        const p = tree?.projects.find((x) => x.id === projectId)
        return p ? `「${p.name}」 프로젝트 아래에 추가` : "추가"
    }

    // SCHEDULE-AC-32 — 달력 클릭/드래그의 빠른 생성 팝오버가 저장을 눌렀을 때
    const quickCreateTask = async (name: string, startIso: string, endIso: string, projectId: number | null) => {
        await scheduleAPI.createTask({
            name, status: "NOT_STARTED", startDate: startIso, endDate: endIso,
            ...(projectId !== null ? { projectId } : {}), milestoneId: null,
        })
        await fetchTree()
    }

    const openCalendarEntry = (kind: ScheduleItemKind, id: number) => {
        if (!tree) return
        if (kind === "task") {
            const found = findTask(id)
            if (found) openEditTask(found.projectId, found.task)
            return
        }
        if (kind === "project") {
            const p = tree.projects.find((x) => x.id === id)
            if (!p) return
            setDialogContext(null)
            setDialogInitial({ name: p.name, status: p.status, startDate: p.startDate, endDate: p.endDate })
            setDialog({ kind: "project", mode: "edit", id: p.id })
            return
        }
        const hit = findMilestone(tree, id)
        if (!hit) return
        setDialogContext(null)
        setDialogInitial({ name: hit.milestone.name, status: hit.milestone.status, startDate: hit.milestone.startDate, endDate: hit.milestone.endDate })
        setDialog({ kind: "milestone", mode: "edit", projectId: hit.projectId, id })
    }

    const openEditTask = (projectId: number | null, task: ScheduleTask) => {
        setDialogContext(null)
        setDialogInitial({ name: task.name, status: task.status, startDate: task.startDate, endDate: task.endDate, color: task.color })
        setDialog({ kind: "task", mode: "edit", projectId, id: task.id })
    }

    const submit = async (draft: ItemDraft) => {
        if (!dialog) return
        const base = { name: draft.name, status: draft.status, startDate: draft.startDate, endDate: draft.endDate }
        if (dialog.kind === "project") {
            if (dialog.mode === "create") await scheduleAPI.createProject(base)
            else await scheduleAPI.updateProject(dialog.id, base)
        } else if (dialog.kind === "milestone") {
            if (dialog.mode === "create") await scheduleAPI.createMilestone({ ...base, projectId: dialog.projectId, parentId: dialog.parentId })
            else {
                await scheduleAPI.updateMilestone(dialog.id, { ...base, parentId: findMilestoneParentId(dialog.id) })
            }
        } else {
            if (dialog.mode === "create") await scheduleAPI.createTask({ ...base, ...(dialog.projectId !== null ? { projectId: dialog.projectId } : {}), milestoneId: dialog.milestoneId })
            else {
                const found = findTask(dialog.id)
                await scheduleAPI.updateTask(dialog.id, { ...base, milestoneId: found?.task.milestoneId ?? null, color: draft.color })
            }
        }
        await fetchTree()
    }

    const remove = async (kind: ItemKind, id: number) => {
        if (!window.confirm(kind === "project" ? "프로젝트와 하위 항목이 모두 삭제됩니다. 계속할까요?"
                : kind === "milestone" ? "마일스톤과 하위 항목이 모두 삭제됩니다. 계속할까요?"
                : "할 일을 삭제할까요?")) return
        if (kind === "project") await scheduleAPI.deleteProject(id)
        else if (kind === "milestone") await scheduleAPI.deleteMilestone(id)
        else await scheduleAPI.deleteTask(id)
        await fetchTree()
    }

    return (
        <main className="mx-auto max-w-4xl space-y-6 p-4 sm:p-6">
            <div className="flex items-center justify-between gap-2">
                <h1 className="text-xl font-bold">일정</h1>
                <div className="flex items-center gap-2">
                    <Button variant="outline" size="sm" aria-label="Slack 알림 설정" onClick={() => setNotifOpen(true)}>
                        <Bell className="h-4 w-4" />
                        <span className="hidden sm:inline sm:ml-1">알림</span>
                    </Button>
                    <Button size="sm" onClick={() => { setDialogInitial(EMPTY_DRAFT); setDialogContext("최상위 묶음입니다 — 아래에 마일스톤과 할 일을 담습니다."); setDialog({ kind: "project", mode: "create" }) }}>
                        <Plus className="mr-1 h-4 w-4" />새 프로젝트
                    </Button>
                </div>
            </div>

            <Tabs value={filter} onValueChange={(v) => setFilter(v as StatusFilter)}>
                <TabsList>
                    <TabsTrigger value="ALL">전체</TabsTrigger>
                    <TabsTrigger value="NOT_STARTED">{STATUS_LABELS.NOT_STARTED}</TabsTrigger>
                    <TabsTrigger value="IN_PROGRESS">{STATUS_LABELS.IN_PROGRESS}</TabsTrigger>
                    <TabsTrigger value="DONE">{STATUS_LABELS.DONE}</TabsTrigger>
                </TabsList>
            </Tabs>

            {treeState === "loading" ? (
                <div className="space-y-3">
                    <Skeleton className="h-24 w-full" />
                    <Skeleton className="h-24 w-full" />
                </div>
            ) : treeState === "failed" ? (
                <Alert variant="destructive">
                    <AlertDescription>
                        일정을 불러오지 못했습니다.{" "}
                        <button type="button" className="underline" onClick={() => void fetchTree()}>다시 시도</button>
                    </AlertDescription>
                </Alert>
            ) : filtered && filtered.projects.length > 0 ? (
                <ScheduleTree
                    tree={filtered}
                    showEmptyHints={filter === "ALL"}
                    cb={{
                        onCycleProject: (p) => void cycleProject(p),
                        onCycleMilestone: (_projectId, m) => void cycleMilestone(m),
                        onCycleTask: (_projectId, task) => void cycleTask(task),
                        onAddMilestone: (projectId, parentId) => { setDialogInitial(EMPTY_DRAFT); setDialogContext(`${parentLabel(projectId, parentId)} — 할 일을 묶는 단계입니다 (5단계까지 중첩).`); setDialog({ kind: "milestone", mode: "create", projectId, parentId }) },
                        // 할 일 생성은 시작일 기본값이 오늘이다 (SCHEDULE-AC-23) — 입력란에서 바꿀 수 있다
                        onAddTask: (projectId, milestoneId) => { setDialogInitial({ ...EMPTY_DRAFT, startDate: todayIso() }); setDialogContext(`${parentLabel(projectId, milestoneId)} — 고유색 막대로 달력에 표시됩니다.`); setDialog({ kind: "task", mode: "create", projectId, milestoneId }) },
                        onEditProject: (p: FilteredProject) => {
                            setDialogContext(null)
                            setDialogInitial({ name: p.name, status: p.status, startDate: p.startDate, endDate: p.endDate })
                            setDialog({ kind: "project", mode: "edit", id: p.id })
                        },
                        onEditMilestone: (projectId, m: FilteredMilestone) => {
                            setDialogContext(null)
                            setDialogInitial({ name: m.name, status: m.status, startDate: m.startDate, endDate: m.endDate })
                            setDialog({ kind: "milestone", mode: "edit", projectId, id: m.id })
                        },
                        onEditTask: openEditTask,
                        onDeleteProject: (id) => void remove("project", id),
                        onDeleteMilestone: (id) => void remove("milestone", id),
                        onDeleteTask: (id) => void remove("task", id),
                    }}
                />
            ) : (
                <div className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
                    {filter === "ALL"
                        ? "아직 프로젝트가 없습니다. 프로젝트 > 마일스톤 > 할 일 구조로 관리합니다 — 새 프로젝트로 시작해 보세요."
                        : "이 상태의 항목이 없습니다."}
                </div>
            )}

            {treeState === "ready" ? (
                <ScheduleCalendar
                    entries={calendarEntries}
                    year={calYear}
                    month={calMonth}
                    onMove={(y, m) => { setCalYear(y); setCalMonth(m) }}
                    onEntryClick={openCalendarEntry}
                    onQuickCreate={quickCreateTask}
                />
            ) : null}

            <NotificationDialog open={notifOpen} onClose={() => setNotifOpen(false)} />

            {dialog ? (
                <ScheduleItemDialog
                    open kind={dialog.kind}
                    title={`${dialog.mode === "create" ? "새 " : ""}${dialog.kind === "project" ? "프로젝트" : dialog.kind === "milestone" ? "마일스톤" : "할 일"}${dialog.mode === "edit" ? " 수정" : ""}`}
                    context={dialogContext ?? undefined}
                    initial={dialogInitial}
                    showColor={dialog.kind === "task" && dialog.mode === "edit"}
                    onSubmit={submit}
                    onClose={() => setDialog(null)}
                />
            ) : null}
        </main>
    )
}

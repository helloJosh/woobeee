"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { Plus } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import ScheduleTree from "@/components/schedule/schedule-tree"
import ScheduleCalendar from "@/components/schedule/schedule-calendar"
import ScheduleItemDialog, { type ItemDraft, type ItemKind } from "@/components/schedule/schedule-item-dialog"
import { useAuth } from "@/hooks/use-auth"
import { buildAuthHref } from "@/lib/auth-redirect"
import { scheduleAPI } from "@/lib/api"
import {
    collectTasks, filterTree, STATUS_LABELS, todayIso,
    type FilteredMilestone, type FilteredProject, type ScheduleTask, type ScheduleTree as Tree, type StatusFilter,
} from "@/lib/schedule"

const SCHEDULE_PATH = "/schedule"
const EMPTY_DRAFT: ItemDraft = { name: "", status: "NOT_STARTED", startDate: null, endDate: null }

/** 어떤 다이얼로그가 열려 있는가. null 이면 닫힘. */
type DialogState =
    | { kind: "project"; mode: "create" }
    | { kind: "project"; mode: "edit"; id: number }
    | { kind: "milestone"; mode: "create"; projectId: number; parentId: number | null }
    | { kind: "milestone"; mode: "edit"; projectId: number; id: number }
    | { kind: "task"; mode: "create"; projectId: number; milestoneId: number | null }
    | { kind: "task"; mode: "edit"; projectId: number; id: number }
    | null

export default function SchedulePage() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()
    const [tree, setTree] = useState<Tree | null>(null)
    const [treeState, setTreeState] = useState<"loading" | "ready" | "failed">("loading")
    const [filter, setFilter] = useState<StatusFilter>("ALL")
    const [dialog, setDialog] = useState<DialogState>(null)
    const [dialogInitial, setDialogInitial] = useState<ItemDraft>(EMPTY_DRAFT)
    const [calYear, setCalYear] = useState(() => new Date().getFullYear())
    const [calMonth, setCalMonth] = useState(() => new Date().getMonth() + 1)

    const fetchTree = useCallback(async () => {
        try {
            setTreeState("loading")
            setTree(await scheduleAPI.getTree())
            setTreeState("ready")
        } catch {
            setTreeState("failed")
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
    const allTasks = tree ? collectTasks(tree) : []
    const calendarTasks = filter === "ALL" ? allTasks : allTasks.filter((t) => t.status === filter)

    const findTask = (taskId: number): { projectId: number; task: ScheduleTask } | null => {
        if (!tree) return null
        for (const p of tree.projects) {
            const hit = collectTasks({ projects: [p] }).find((t) => t.id === taskId)
            if (hit) return { projectId: p.id, task: hit }
        }
        return null
    }

    const openEditTask = (projectId: number, task: ScheduleTask) => {
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
                const current = tree?.projects.flatMap(function walk(p): { id: number; parentId: number | null }[] {
                    const list: { id: number; parentId: number | null }[] = []
                    const visit = (ms: typeof p.milestones, parentId: number | null) => {
                        for (const m of ms) { list.push({ id: m.id, parentId }); visit(m.milestones, m.id) }
                    }
                    visit(p.milestones, null)
                    return list
                }).find((m) => m.id === dialog.id)
                await scheduleAPI.updateMilestone(dialog.id, { ...base, parentId: current?.parentId ?? null })
            }
        } else {
            if (dialog.mode === "create") await scheduleAPI.createTask({ ...base, projectId: dialog.projectId, milestoneId: dialog.milestoneId })
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
                <Button size="sm" onClick={() => { setDialogInitial(EMPTY_DRAFT); setDialog({ kind: "project", mode: "create" }) }}>
                    <Plus className="mr-1 h-4 w-4" />새 프로젝트
                </Button>
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
                    cb={{
                        onAddMilestone: (projectId, parentId) => { setDialogInitial(EMPTY_DRAFT); setDialog({ kind: "milestone", mode: "create", projectId, parentId }) },
                        // 할 일 생성은 시작일 기본값이 오늘이다 (SCHEDULE-AC-23) — 입력란에서 바꿀 수 있다
                        onAddTask: (projectId, milestoneId) => { setDialogInitial({ ...EMPTY_DRAFT, startDate: todayIso() }); setDialog({ kind: "task", mode: "create", projectId, milestoneId }) },
                        onEditProject: (p: FilteredProject) => {
                            setDialogInitial({ name: p.name, status: p.status, startDate: p.startDate, endDate: p.endDate })
                            setDialog({ kind: "project", mode: "edit", id: p.id })
                        },
                        onEditMilestone: (projectId, m: FilteredMilestone) => {
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
                    {filter === "ALL" ? "아직 프로젝트가 없습니다. 새 프로젝트로 시작해 보세요." : "이 상태의 항목이 없습니다."}
                </div>
            )}

            {treeState === "ready" ? (
                <ScheduleCalendar
                    tasks={calendarTasks}
                    year={calYear}
                    month={calMonth}
                    onMove={(y, m) => { setCalYear(y); setCalMonth(m) }}
                    onTaskClick={(taskId) => {
                        const found = findTask(taskId)
                        if (found) openEditTask(found.projectId, found.task)
                    }}
                />
            ) : null}

            {dialog ? (
                <ScheduleItemDialog
                    open kind={dialog.kind}
                    title={`${dialog.mode === "create" ? "새 " : ""}${dialog.kind === "project" ? "프로젝트" : dialog.kind === "milestone" ? "마일스톤" : "할 일"}${dialog.mode === "edit" ? " 수정" : ""}`}
                    initial={dialogInitial}
                    showColor={dialog.kind === "task" && dialog.mode === "edit"}
                    onSubmit={submit}
                    onClose={() => setDialog(null)}
                />
            ) : null}
        </main>
    )
}

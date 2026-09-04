"use client"

import { useState } from "react"
import { ChevronDown, ChevronRight, ListTodo, Milestone, MoreHorizontal, Plus } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
    DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { formatDateRange, formatTaskRange, STATUS_LABELS, type FilteredMilestone, type FilteredProject, type FilteredTree, type ScheduleStatus, type ScheduleTask } from "@/lib/schedule"

export interface TreeCallbacks {
    onCycleProject: (project: FilteredProject) => void
    onCycleMilestone: (projectId: number, milestone: FilteredMilestone) => void
    onCycleTask: (projectId: number | null, task: ScheduleTask) => void
    onAddMilestone: (projectId: number, parentId: number | null) => void
    /** projectId null = 무소속 할 일 (SCHEDULE-AC-31). */
    onAddTask: (projectId: number | null, milestoneId: number | null) => void
    onEditProject: (project: FilteredProject) => void
    onEditMilestone: (projectId: number, milestone: FilteredMilestone) => void
    onEditTask: (projectId: number | null, task: ScheduleTask) => void
    onDeleteProject: (projectId: number) => void
    onDeleteMilestone: (milestoneId: number) => void
    onDeleteTask: (taskId: number) => void
}

const STATUS_BADGE_CLASS: Record<ScheduleStatus, string> = {
    NOT_STARTED: "bg-muted text-muted-foreground",
    IN_PROGRESS: "bg-blue-500/15 text-blue-600 dark:text-blue-400",
    DONE: "bg-green-500/15 text-green-600 dark:text-green-400",
}

function StatusBadge({ status, onClick }: { status: ScheduleStatus; onClick?: () => void }) {
    const badge = (
        <Badge variant="outline" className={`border-transparent ${STATUS_BADGE_CLASS[status]}`}>
            {STATUS_LABELS[status]}
        </Badge>
    )
    if (!onClick) return badge
    // SCHEDULE-AC-29 — 클릭할 때마다 시작전→진행중→완료 순환
    return (
        <button type="button" onClick={onClick} title="클릭하면 상태가 바뀝니다" aria-label="상태 변경">
            {badge}
        </button>
    )
}

/** 추가는 + 메뉴 한 곳으로 — 무엇을 만드는지 라벨로 보여준다. ⋯ 는 수정/삭제 전용. */
function AddMenu({ onAddTask, onAddMilestone, milestoneLabel }: {
    onAddTask: () => void
    onAddMilestone: () => void
    milestoneLabel: string
}) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="항목 추가">
                    <Plus className="h-4 w-4" />
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={onAddTask}>
                    <ListTodo className="mr-2 h-4 w-4" />할 일 추가
                </DropdownMenuItem>
                <DropdownMenuItem onClick={onAddMilestone}>
                    <Milestone className="mr-2 h-4 w-4" />{milestoneLabel}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    )
}

function TaskRow({ projectId, task, cb }: { projectId: number | null; task: ScheduleTask; cb: TreeCallbacks }) {
    const done = task.status === "DONE"
    return (
        <li className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-muted/50">
            <StatusBadge status={task.status} onClick={() => cb.onCycleTask(projectId, task)} />
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: task.color }} />
            <span className={`flex-1 truncate text-sm ${done ? "text-muted-foreground line-through" : ""}`}>{task.name}</span>
            <span className="hidden text-xs text-muted-foreground sm:inline">{formatTaskRange(task.startDate, task.endDate, task.startTime, task.endTime)}</span>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => cb.onEditTask(projectId, task)}>수정</DropdownMenuItem>
                    <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteTask(task.id)}>삭제</DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </li>
    )
}

function MilestoneRow({ projectId, milestone, cb }: {
    projectId: number; milestone: FilteredMilestone; cb: TreeCallbacks
}) {
    const [open, setOpen] = useState(true)
    return (
        <li>
            <div className={`flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-muted/50 ${milestone.dimmed ? "opacity-50" : ""}`}>
                <button type="button" onClick={() => setOpen(!open)} className="text-muted-foreground">
                    {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                </button>
                <StatusBadge status={milestone.status} onClick={() => cb.onCycleMilestone(projectId, milestone)} />
                <span className="flex-1 truncate text-sm font-medium">{milestone.name}</span>
                <span className="hidden text-xs text-muted-foreground sm:inline">{formatDateRange(milestone.startDate, milestone.endDate)}</span>
                <AddMenu
                    onAddTask={() => cb.onAddTask(projectId, milestone.id)}
                    onAddMilestone={() => cb.onAddMilestone(projectId, milestone.id)}
                    milestoneLabel="하위 마일스톤 추가"
                />
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => cb.onEditMilestone(projectId, milestone)}>수정</DropdownMenuItem>
                        <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteMilestone(milestone.id)}>삭제</DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>
            {open ? (
                <ul className="ml-5 border-l pl-2">
                    {milestone.tasks.map((t) => <TaskRow key={t.id} projectId={projectId} task={t} cb={cb} />)}
                    {milestone.milestones.map((m) => (
                        <MilestoneRow key={m.id} projectId={projectId} milestone={m} cb={cb} />
                    ))}
                </ul>
            ) : null}
        </li>
    )
}

export default function ScheduleTree({ tree, cb, showEmptyHints }: {
    tree: FilteredTree
    cb: TreeCallbacks
    /** 상태 필터가 꺼져 있을 때만 — 필터로 비어 보이는 프로젝트에 잘못된 안내를 하지 않기 위해. */
    showEmptyHints: boolean
}) {
    return (
        <ul className="space-y-4">
            {tree.projects.map((p) => (
                <li key={p.id} className={`rounded-lg border p-3 ${p.dimmed ? "opacity-50" : ""}`}>
                    <div className="flex items-center gap-2">
                        <StatusBadge status={p.status} onClick={() => cb.onCycleProject(p)} />
                        <span className="flex-1 truncate font-semibold">{p.name}</span>
                        <span className="hidden text-xs text-muted-foreground sm:inline">{formatDateRange(p.startDate, p.endDate)}</span>
                        <AddMenu
                            onAddTask={() => cb.onAddTask(p.id, null)}
                            onAddMilestone={() => cb.onAddMilestone(p.id, null)}
                            milestoneLabel="마일스톤 추가"
                        />
                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon" className="h-7 w-7"><MoreHorizontal className="h-4 w-4" /></Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end">
                                <DropdownMenuItem onClick={() => cb.onEditProject(p)}>수정</DropdownMenuItem>
                                <DropdownMenuItem className="text-destructive" onClick={() => cb.onDeleteProject(p.id)}>삭제</DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenu>
                    </div>
                    <ul className="mt-2 space-y-0.5">
                        {p.tasks.map((t) => <TaskRow key={t.id} projectId={p.id} task={t} cb={cb} />)}
                        {p.milestones.map((m) => (
                            <MilestoneRow key={m.id} projectId={p.id} milestone={m} cb={cb} />
                        ))}
                    </ul>
                    {showEmptyHints && p.tasks.length === 0 && p.milestones.length === 0 ? (
                        <div className="mt-1 flex items-center gap-2 px-2 py-1 text-sm text-muted-foreground">
                            아직 항목이 없습니다 —
                            <Button variant="outline" size="sm" className="h-7"
                                    onClick={() => cb.onAddTask(p.id, null)}>
                                <ListTodo className="mr-1 h-3.5 w-3.5" />할 일
                            </Button>
                            <Button variant="outline" size="sm" className="h-7"
                                    onClick={() => cb.onAddMilestone(p.id, null)}>
                                <Milestone className="mr-1 h-3.5 w-3.5" />마일스톤
                            </Button>
                        </div>
                    ) : null}
                </li>
            ))}
            <li className="rounded-lg border p-3">
                <div className="flex items-center gap-2">
                    <span className="flex-1 truncate font-semibold">바로 할 일</span>
                    <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="바로 할 일 추가"
                            onClick={() => cb.onAddTask(null, null)}>
                        <Plus className="h-4 w-4" />
                    </Button>
                </div>
                {tree.tasks.length > 0 ? (
                    <ul className="mt-2 space-y-0.5">
                        {tree.tasks.map((t) => <TaskRow key={t.id} projectId={null} task={t} cb={cb} />)}
                    </ul>
                ) : showEmptyHints ? (
                    <p className="mt-1 px-2 py-1 text-sm text-muted-foreground">
                        어느 프로젝트에도 속하지 않는 할 일을 여기에 담습니다 — 달력의 빈 날짜를 클릭하거나 드래그해도 만들어집니다.
                    </p>
                ) : null}
            </li>
        </ul>
    )
}

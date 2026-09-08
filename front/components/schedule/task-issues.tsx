"use client"

import { useState } from "react"
import { Plus, Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import type { ScheduleIssue } from "@/lib/schedule"

/** SCHEDULE-AC-42 — 이슈 패널이 부모(페이지)에 부탁하는 일. 저장 뒤 트리 갱신은 부모가 한다. */
export interface IssueCallbacks {
    onAddIssue: (taskId: number, content: string) => Promise<void>
    /** 해결 체크 — 옵티미스틱 반영 대상이라 동기 콜백이다. */
    onToggleIssue: (issue: ScheduleIssue, resolved: boolean) => void
    onEditIssue: (issue: ScheduleIssue, content: string) => Promise<void>
    onDeleteIssue: (issueId: number) => void
}

const MAX_CONTENT = 1000

function IssueRow({ issue, cb }: { issue: ScheduleIssue; cb: IssueCallbacks }) {
    const [editing, setEditing] = useState<string | null>(null)

    const commit = async () => {
        if (editing === null) return
        const content = editing.trim()
        setEditing(null)
        if (content === "" || content === issue.content) return
        await cb.onEditIssue(issue, content)
    }

    return (
        <li className="flex items-start gap-2 rounded px-1 py-1 hover:bg-muted/40">
            <Checkbox
                className="mt-0.5"
                aria-label={issue.resolved ? "미해결로 되돌리기" : "해결로 표시"}
                checked={issue.resolved}
                onCheckedChange={(v) => cb.onToggleIssue(issue, v === true)}
            />
            {editing !== null ? (
                <Input
                    autoFocus
                    className="h-7 flex-1 text-sm"
                    value={editing}
                    maxLength={MAX_CONTENT}
                    onChange={(e) => setEditing(e.target.value)}
                    onBlur={() => void commit()}
                    onKeyDown={(e) => {
                        if (e.key === "Enter") { e.preventDefault(); void commit() }
                        if (e.key === "Escape") setEditing(null)
                    }}
                />
            ) : (
                <button
                    type="button"
                    title="클릭하면 내용을 고칩니다"
                    className={`flex-1 whitespace-pre-wrap break-words text-left text-sm ${issue.resolved ? "text-muted-foreground line-through" : ""}`}
                    onClick={() => setEditing(issue.content)}
                >
                    {issue.content}
                </button>
            )}
            <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0 text-muted-foreground hover:text-destructive"
                    aria-label="이슈 삭제" onClick={() => cb.onDeleteIssue(issue.id)}>
                <Trash2 className="h-3.5 w-3.5" />
            </Button>
        </li>
    )
}

/**
 * SCHEDULE-AC-42 — 할 일 행 아래에 접혀 있는 이슈사항 목록. 달력과는 무관하다.
 * 체크 = 해결, 내용 클릭 = 수정, 맨 아래 한 줄 입력 = 추가.
 */
export default function TaskIssues({ taskId, issues, cb }: {
    taskId: number
    issues: ScheduleIssue[]
    cb: IssueCallbacks
}) {
    const [draft, setDraft] = useState("")
    const [saving, setSaving] = useState(false)

    const add = async () => {
        const content = draft.trim()
        if (content === "" || saving) return
        setSaving(true)
        try {
            await cb.onAddIssue(taskId, content)
            setDraft("")
        } finally {
            setSaving(false)
        }
    }

    return (
        <div className="ml-8 mt-1 mb-2 rounded-md border border-dashed bg-muted/20 p-2">
            {issues.length > 0 ? (
                <ul className="space-y-0.5">
                    {issues.map((i) => <IssueRow key={i.id} issue={i} cb={cb} />)}
                </ul>
            ) : (
                <p className="px-1 pb-1 text-xs text-muted-foreground">아직 이슈가 없습니다.</p>
            )}
            <form
                className="mt-1 flex items-center gap-2"
                onSubmit={(e) => { e.preventDefault(); void add() }}
            >
                <Input
                    className="h-7 flex-1 text-sm"
                    placeholder="이슈사항 추가 — Enter 로 저장"
                    aria-label="새 이슈 내용"
                    value={draft}
                    maxLength={MAX_CONTENT}
                    onChange={(e) => setDraft(e.target.value)}
                />
                <Button type="submit" variant="outline" size="sm" className="h-7" disabled={saving || draft.trim() === ""}>
                    <Plus className="mr-1 h-3.5 w-3.5" />추가
                </Button>
            </form>
        </div>
    )
}

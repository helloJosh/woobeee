"use client"

import { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { scheduleAPI } from "@/lib/api"
import { isValidSlackWebhookUrl } from "@/lib/schedule"

interface Props {
    open: boolean
    onClose: () => void
}

/** Slack 다이제스트 설정 — URL 등록/해제만 한다. 판단(URL 검증)은 lib/schedule.ts 의 몫. */
export default function NotificationDialog({ open, onClose }: Props) {
    const [url, setUrl] = useState("")
    const [registered, setRegistered] = useState<string | null>(null)
    const [state, setState] = useState<"loading" | "ready" | "failed">("loading")
    const [saving, setSaving] = useState(false)

    useEffect(() => {
        if (!open) return
        let cancelled = false
        setState("loading")
        scheduleAPI.getNotification()
            .then((res) => {
                if (cancelled) return
                setRegistered(res.webhookUrl)
                setUrl(res.webhookUrl ?? "")
                setState("ready")
            })
            .catch(() => {
                if (!cancelled) setState("failed")
            })
        return () => {
            cancelled = true
        }
    }, [open])

    const trimmed = url.trim()
    const invalid = trimmed !== "" && !isValidSlackWebhookUrl(trimmed)

    const save = async () => {
        setSaving(true)
        try {
            await scheduleAPI.updateNotification(trimmed)
            onClose()
        } finally {
            setSaving(false)
        }
    }

    const remove = async () => {
        setSaving(true)
        try {
            await scheduleAPI.deleteNotification()
            setUrl("")
            onClose()
        } finally {
            setSaving(false)
        }
    }

    return (
        <Dialog open={open} onOpenChange={(v) => { if (!v) onClose() }}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader><DialogTitle>Slack 알림 설정</DialogTitle></DialogHeader>
                {state === "failed" ? (
                    <p className="text-sm text-destructive">설정을 불러오지 못했습니다. 닫았다가 다시 열어 주세요.</p>
                ) : (
                    <div className="space-y-3">
                        <p className="text-sm text-muted-foreground">
                            매일 아침 9시에 오늘 마감·오늘 시작·기한이 지난 할 일 요약을 Slack으로 보냅니다.
                        </p>
                        <div className="space-y-2">
                            <Label htmlFor="slack-webhook-url">Incoming Webhook URL</Label>
                            <Input
                                id="slack-webhook-url"
                                placeholder="https://hooks.slack.com/services/..."
                                value={url}
                                disabled={state === "loading"}
                                onChange={(e) => setUrl(e.target.value)}
                            />
                        </div>
                        {invalid ? (
                            <p className="text-sm text-destructive">
                                Slack Webhook URL은 https://hooks.slack.com/ 으로 시작해야 합니다.
                            </p>
                        ) : null}
                    </div>
                )}
                <DialogFooter>
                    {registered !== null ? (
                        <Button variant="outline" className="text-destructive" onClick={remove} disabled={saving}>
                            알림 해제
                        </Button>
                    ) : null}
                    <Button variant="outline" onClick={onClose} disabled={saving}>취소</Button>
                    <Button onClick={save} disabled={saving || state !== "ready" || trimmed === "" || invalid}>
                        {saving ? "저장 중..." : "저장"}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    )
}

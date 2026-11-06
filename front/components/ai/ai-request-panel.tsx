"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/hooks/use-auth"
import { aiAPI } from "@/lib/api"
import {
    POLL_INTERVAL_MS, canSubmit, describeAction, imageErrorMessage, shouldPoll, statusLabel, validateImages,
    type AiRequest,
} from "@/lib/ai"
import { buildAuthHref } from "@/lib/auth-redirect"

const AI_PATH = "/ai"

/**
 * 화면은 얇게 둔다 — 한도 검사·폴링 여부·제출 가능 여부 같은 판단은 lib/ai.ts 에 있다.
 * 흐름: 글·이미지 제출 → 진행 중이면 3초마다 다시 읽기 → DRAFTED 면 제안별로 승인/거절.
 */
export default function AiRequestPanel() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()
    const [latest, setLatest] = useState<AiRequest | null>(null)
    const [loaded, setLoaded] = useState(false)
    const [text, setText] = useState("")
    const [files, setFiles] = useState<File[]>([])
    const [previews, setPreviews] = useState<string[]>([])
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const fileInput = useRef<HTMLInputElement>(null)

    useEffect(() => {
        if (!loading && !isAuthenticated) {
            router.replace(buildAuthHref("/login", AI_PATH))
        }
    }, [loading, isAuthenticated, router])

    // 처음 들어오면 내 최근 요청을 찾는다 — 새로고침해도 진행 중인 요청을 놓치지 않는다.
    useEffect(() => {
        if (loading || !isAuthenticated) return
        let cancelled = false
        aiAPI.latest()
            .then((request) => { if (!cancelled) setLatest(request) })
            .catch((e: Error) => { if (!cancelled) setError(e.message) })
            .finally(() => { if (!cancelled) setLoaded(true) })
        return () => { cancelled = true }
    }, [loading, isAuthenticated])

    // 진행 중인 동안만 다시 읽는다. 상태가 바뀌거나 화면을 떠나면 타이머를 정리한다.
    const polledId = latest && shouldPoll(latest.status) ? latest.id : null
    useEffect(() => {
        if (!polledId) return
        const timer = setInterval(() => {
            aiAPI.get(polledId).then(setLatest).catch(() => { /* 다음 주기에 다시 한다 */ })
        }, POLL_INTERVAL_MS)
        return () => clearInterval(timer)
    }, [polledId])

    // 선택한 이미지의 미리보기 URL — 바뀌거나 떠날 때 반드시 반납한다.
    useEffect(() => {
        const urls = files.map((file) => URL.createObjectURL(file))
        setPreviews(urls)
        return () => urls.forEach((url) => URL.revokeObjectURL(url))
    }, [files])

    const run = useCallback(async (action: () => Promise<AiRequest>) => {
        setBusy(true)
        setError(null)
        try {
            setLatest(await action())
        } catch (e) {
            setError(e instanceof Error ? e.message : "요청을 처리하지 못했습니다.")
        } finally {
            setBusy(false)
        }
    }, [])

    const submit = async () => {
        const problem = validateImages(files)
        if (problem) {
            setError(imageErrorMessage(problem))
            return
        }
        if (!text.trim() && files.length === 0) {
            setError("글을 쓰거나 이미지를 올려 주세요.")
            return
        }
        const form = new FormData()
        if (text.trim()) form.append("text", text)
        files.forEach((file) => form.append("images", file))
        await run(() => aiAPI.create(form))
        setText("")
        setFiles([])
        if (fileInput.current) fileInput.current.value = ""
    }

    if (loading || !isAuthenticated || !loaded) {
        return <div className="mx-auto max-w-2xl p-4"><Skeleton className="h-40 w-full" /></div>
    }

    const submittable = canSubmit(latest)

    return (
        <main className="mx-auto flex max-w-2xl flex-col gap-6 p-4">
            <h1 className="text-xl font-semibold">AI 요청하기</h1>
            <p className="text-sm text-muted-foreground">
                상품 목록 스크린샷이나 일정을 글로 알려 주면 AI 가 등록할 내용을 제안해요. 제안은 직접 승인해야 등록됩니다.
            </p>

            {error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}

            <section aria-label="새 요청" className="flex flex-col gap-3 rounded-lg border p-4">
                <textarea
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    disabled={!submittable || busy}
                    rows={4}
                    maxLength={4000}
                    placeholder="예) 7차에 이 상품들 넣어줘 / 다음 주 금요일 14:30 치과 예약"
                    className="w-full rounded-md border bg-background p-2 text-sm"
                />
                <input
                    ref={fileInput}
                    type="file"
                    accept="image/png,image/jpeg,image/webp"
                    multiple
                    disabled={!submittable || busy}
                    onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
                    className="text-sm"
                />
                {previews.length > 0 ? (
                    <div className="flex flex-wrap gap-2">
                        {previews.map((url, index) => (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img key={url} src={url} alt={`첨부 ${index + 1}`} className="h-20 w-20 rounded border object-cover" />
                        ))}
                    </div>
                ) : null}
                {submittable ? null : (
                    <p className="text-sm text-muted-foreground">이전 요청이 끝나면 보낼 수 있어요.</p>
                )}
                <Button onClick={submit} disabled={!submittable || busy} className="self-start">
                    {busy ? "보내는 중…" : "AI 에게 보내기"}
                </Button>
            </section>

            {latest ? <LatestRequest request={latest} busy={busy} run={run} /> : null}
        </main>
    )
}

function LatestRequest({ request, busy, run }: {
    request: AiRequest
    busy: boolean
    run: (action: () => Promise<AiRequest>) => Promise<void>
}) {
    return (
        <section aria-label="최근 요청" className="flex flex-col gap-3 rounded-lg border p-4">
            <div className="flex items-center justify-between">
                <h2 className="font-medium">{statusLabel(request.status)}</h2>
                <span className="text-xs text-muted-foreground">{new Date(request.createdAt).toLocaleString("ko-KR")}</span>
            </div>
            {request.text ? <p className="whitespace-pre-wrap text-sm">{request.text}</p> : null}
            {request.imageUrls.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                    {request.imageUrls.map((url, index) => (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img key={url} src={url} alt={`요청 이미지 ${index + 1}`} className="h-16 w-16 rounded border object-cover" />
                    ))}
                </div>
            ) : null}

            {request.status === "DEAD" ? (
                <div className="flex flex-col gap-2">
                    <p className="text-sm text-destructive">{request.lastError ?? "처리하지 못했어요."}</p>
                    <Button variant="outline" disabled={busy} onClick={() => run(() => aiAPI.retry(request.id))} className="self-start">
                        다시 시도
                    </Button>
                </div>
            ) : null}

            {request.status === "DRAFTED" || request.status === "APPLIED" ? (
                <div className="flex flex-col gap-3">
                    {request.summary ? <p className="text-sm">{request.summary}</p> : null}
                    {request.actions.length === 0 ? (
                        <p className="text-sm text-muted-foreground">제안 없음 — 등록할 내용을 찾지 못했어요.</p>
                    ) : (
                        <ul className="flex flex-col gap-2">
                            {request.actions.map((action) => (
                                <li key={action.id} className="flex items-center justify-between gap-2 rounded border p-2 text-sm">
                                    <span>{describeAction(action)}</span>
                                    {action.status === "PENDING" ? (
                                        <span className="flex gap-2">
                                            <Button size="sm" disabled={busy} onClick={() => run(() => aiAPI.apply(request.id, action.id))}>승인</Button>
                                            <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => aiAPI.reject(request.id, action.id))}>거절</Button>
                                        </span>
                                    ) : (
                                        <span className="text-xs text-muted-foreground">{action.status === "APPLIED" ? "등록됨" : "거절함"}</span>
                                    )}
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
            ) : null}
        </section>
    )
}

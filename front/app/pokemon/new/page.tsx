"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Textarea } from "@/components/ui/textarea"
import { Field } from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    RATE_MODE_LABELS,
    validateHandle,
    validateRoundDraft,
    type PokemonHome,
    type PokemonRateMode,
    type PokemonRoundDraft,
} from "@/lib/pokemon"

const EMPTY: PokemonRoundDraft = {
    title: "", currency: "INR", rateMode: "FIXED", quotedRate: "",
    bankAccount: "", deadline: "", memo: "",
}

/**
 * 차수 개설. 주소(handle)를 아직 정하지 않았으면 먼저 정하게 한다 —
 * 주소가 있어야 /pokemon/{handle}/{n} 이 생긴다.
 */
export default function PokemonNewRoundPage() {
    const router = useRouter()
    const [home, setHome] = useState<PokemonHome | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [handle, setHandle] = useState("")
    const [draft, setDraft] = useState<PokemonRoundDraft>(EMPTY)
    const [busy, setBusy] = useState(false)

    const load = useCallback(async () => {
        try {
            const loaded = await pokemonAPI.getHome()
            setHome(loaded)
            setDraft((d) => ({
                ...d,
                currency: loaded.currencies[0] ?? "INR",
                // 상품 관리에서 정해 둔 기본 계좌를 미리 채운다 — 차수마다 다시 치지 않게.
                bankAccount: d.bankAccount || (loaded.myBankAccount ?? ""),
            }))
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "불러오지 못했습니다."))
        }
    }, [])

    useEffect(() => {
        void load()
    }, [load])

    const claim = async () => {
        const problem = validateHandle(handle)
        if (problem !== null) {
            setError(problem)
            return
        }
        setBusy(true)
        try {
            await pokemonAPI.claimHandle(handle.trim().toLowerCase())
            await load()
            setError(null)
        } catch (caught) {
            setError(describeGameApiError(caught, "주소를 정하지 못했습니다."))
        } finally {
            setBusy(false)
        }
    }

    const open = async () => {
        const problem = validateRoundDraft(draft, home?.currencies ?? [], home?.myBankAccount !== null)
        if (problem !== null) {
            setError(problem)
            return
        }
        setBusy(true)
        try {
            const created = await pokemonAPI.openRound({
                title: draft.title.trim() || undefined,
                currency: draft.currency,
                rateMode: draft.rateMode,
                quotedRate: draft.quotedRate.trim() === "" ? undefined : Number(draft.quotedRate.trim()),
                bankAccount: draft.bankAccount.trim() || undefined,
                deadline: draft.deadline.trim() === "" ? null : draft.deadline,
                memo: draft.memo.trim() || undefined,
            })
            router.replace(`/pokemon/${created.hostHandle}/${created.sequence}`)
        } catch (caught) {
            setError(describeGameApiError(caught, "공동구매를 열지 못했습니다."))
            setBusy(false)
        }
    }

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-2xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || home === null) {
        return (
            <main className="mx-auto max-w-2xl px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{error}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    const header = (
        <header className="space-y-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2">
                <Link href="/pokemon">
                    <ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록
                </Link>
            </Button>
            <h1 className="text-2xl font-bold">공동구매 열기</h1>
        </header>
    )

    const errorBox = error !== null && (
        <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertDescription>{error}</AlertDescription>
        </Alert>
    )

    if (!home.loggedIn) {
        return (
            <main className="mx-auto max-w-2xl space-y-6 px-4 py-8">
                {header}
                <Alert>
                    <AlertDescription>
                        공동구매를 열려면 로그인이 필요합니다. 신청은 로그인 없이도 됩니다.
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    // 주소가 없으면 차수를 열 수 없다 — URL 이 주소 위에 세워지기 때문이다.
    if (home.myHandle === null) {
        return (
            <main className="mx-auto max-w-2xl space-y-6 px-4 py-8">
                {header}
                {errorBox}
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">먼저 내 주소를 정하세요</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3">
                        <Field label="주소 (영소문자·숫자·하이픈)">
                            <Input
                                value={handle}
                                onChange={(e) => setHandle(e.target.value)}
                                placeholder="byungwoo"
                                maxLength={30}
                            />
                        </Field>
                        <p className="text-xs text-muted-foreground">
                            내가 여는 공동구매가 <b>/pokemon/{handle.trim().toLowerCase() || "주소"}</b> 에
                            모입니다. <b>한 번 정하면 바꿀 수 없습니다</b> — 바꾸면 이전 주소로 공유한
                            링크가 전부 죽기 때문입니다.
                        </p>
                        <Button onClick={() => void claim()} disabled={busy}>
                            이 주소로 정하기
                        </Button>
                    </CardContent>
                </Card>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-2xl space-y-6 px-4 py-8">
            {header}
            {errorBox}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">
                        /pokemon/{home.myHandle} 에 새 차수를 엽니다
                    </CardTitle>
                </CardHeader>
                <CardContent className="grid gap-3 sm:grid-cols-2">
                    <div className="sm:col-span-2">
                        <Field label="제목 (선택 — 비우면 'N차 공동구매')">
                            <Input
                                value={draft.title}
                                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                                placeholder="예) 추석 공구"
                                maxLength={100}
                            />
                        </Field>
                    </div>

                    <Field label="통화 (나중에 못 바꿉니다)">
                        <Select
                            value={draft.currency}
                            onValueChange={(v) => setDraft({ ...draft, currency: v })}
                        >
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {home.currencies.map((c) => (
                                    <SelectItem key={c} value={c}>{c}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </Field>

                    <Field label="환율 방식">
                        <Select
                            value={draft.rateMode}
                            onValueChange={(v) => setDraft({ ...draft, rateMode: v as PokemonRateMode })}
                        >
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {(["FIXED", "PER_ORDER"] as const).map((m) => (
                                    <SelectItem key={m} value={m}>{RATE_MODE_LABELS[m]}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </Field>

                    {draft.rateMode === "FIXED" && (
                        <div className="sm:col-span-2">
                            <Field label="환율 (비우면 지금 환율)">
                                <Input
                                    value={draft.quotedRate}
                                    onChange={(e) => setDraft({ ...draft, quotedRate: e.target.value })}
                                    inputMode="decimal"
                                    placeholder="예) 14.13"
                                />
                            </Field>
                            <p className="mt-2 text-xs text-muted-foreground">
                                이 차수의 모든 신청서가 이 환율로 계산됩니다. 나중에 고칠 수 있지만
                                <b> 이미 들어온 신청서는 각자의 환율을 유지합니다</b> — 알려 준 이체
                                금액이 나중에 달라지면 안 되니까요.
                            </p>
                        </div>
                    )}

                    <div className="sm:col-span-2">
                        <Field label="입금받을 계좌">
                            <Input
                                value={draft.bankAccount}
                                onChange={(e) => setDraft({ ...draft, bankAccount: e.target.value })}
                                placeholder="예) 하나은행 12345678 홍길동"
                                maxLength={200}
                            />
                        </Field>
                        {home.myBankAccount !== null && (
                            <p className="mt-1 text-xs text-muted-foreground">
                                상품 관리에 정해 둔 기본 계좌가 채워졌습니다. 이 차수만 다르게 하려면 고치세요.
                            </p>
                        )}
                    </div>

                    <Field label="마감일 (선택)">
                        <Input
                            type="date"
                            value={draft.deadline}
                            onChange={(e) => setDraft({ ...draft, deadline: e.target.value })}
                        />
                    </Field>

                    <div className="sm:col-span-2">
                        <Field label="메모 (선택)">
                            <Textarea
                                value={draft.memo}
                                onChange={(e) => setDraft({ ...draft, memo: e.target.value })}
                                rows={2}
                                maxLength={500}
                            />
                        </Field>
                    </div>

                    <div className="sm:col-span-2">
                        <Button onClick={() => void open()} disabled={busy} className="w-full">
                            {busy ? "여는 중…" : "공동구매 열기"}
                        </Button>
                    </div>
                </CardContent>
            </Card>
        </main>
    )
}

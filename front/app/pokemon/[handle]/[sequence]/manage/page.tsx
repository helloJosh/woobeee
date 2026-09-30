"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useParams } from "next/navigation"
import { AlertTriangle, ArrowLeft, Copy, Plus, RefreshCw, Trash2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
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
    RATE_MODE_LABELS, formatAmount, formatCoins, formatRate, parseAmount, parseCoins,
    roundTitle, validateProductDraft, validateRoundDraft,
    type PokemonManagedProduct, type PokemonRateMode, type PokemonRoundBoard,
    type PokemonRoundDraft,
} from "@/lib/pokemon"

/**
 * 차수 관리 — 그 차수의 환율·계좌·마감일과 <b>그 차수의 상품표</b>를 고친다.
 *
 * <p>상품표가 차수마다 따로이므로 여기가 그 차수의 것을 만지는 유일한 곳이다.
 * `/pokemon/products` 는 다음 차수에 복사될 <b>틀</b>을 다룬다.
 */
export default function PokemonRoundManagePage() {
    const params = useParams<{ handle: string; sequence: string }>()
    const handle = params?.handle ?? ""
    const sequence = Number(params?.sequence)

    const [board, setBoard] = useState<PokemonRoundBoard | null>(null)
    const [products, setProducts] = useState<PokemonManagedProduct[]>([])
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [draft, setDraft] = useState<PokemonRoundDraft | null>(null)
    const [productDraft, setProductDraft] = useState({ name: "", price: "", coins: "" })
    const [busy, setBusy] = useState(false)

    const load = useCallback(async () => {
        if (!Number.isInteger(sequence)) {
            setLoadState("failed")
            setError("공동구매를 찾을 수 없습니다.")
            return
        }
        try {
            const loaded = await pokemonAPI.getRoundBoard(handle, sequence)
            setBoard(loaded)
            setDraft({
                title: loaded.round.title ?? "",
                currency: loaded.round.currency,
                rateMode: loaded.round.rateMode,
                quotedRate: String(loaded.round.quotedRate),
                bankAccount: loaded.round.bankAccount,
                deadline: loaded.round.deadline ?? "",
                memo: loaded.round.memo ?? "",
            })
            if (loaded.round.canManage) {
                setProducts(await pokemonAPI.getRoundProducts(loaded.round.id))
            }
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "불러오지 못했습니다."))
        }
    }, [handle, sequence])

    useEffect(() => {
        void load()
    }, [load])

    const run = async (action: () => Promise<unknown>, fallback: string) => {
        setBusy(true)
        try {
            await action()
            await load()
            setError(null)
            return true
        } catch (caught) {
            setError(describeGameApiError(caught, fallback))
            return false
        } finally {
            setBusy(false)
        }
    }

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-3xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || board === null || draft === null) {
        return (
            <main className="mx-auto max-w-3xl space-y-3 px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{error}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
                <Button asChild variant="ghost" size="sm">
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
                </Button>
            </main>
        )
    }

    const { round, currentRate } = board
    const backToRound = `/pokemon/${round.hostHandle}/${round.sequence}`

    const header = (
        <header className="space-y-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2">
                <Link href={backToRound}>
                    <ArrowLeft className="mr-1 h-4 w-4" /> {roundTitle(round)}
                </Link>
            </Button>
            <h1 className="text-2xl font-bold">차수 관리</h1>
        </header>
    )

    if (!round.canManage) {
        return (
            <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
                {header}
                <Alert>
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>이 공동구매를 연 사람만 관리할 수 있습니다.</AlertDescription>
                </Alert>
            </main>
        )
    }

    const saveRound = async () => {
        const problem = validateRoundDraft(draft, [round.currency], true)
        if (problem !== null) {
            setError(problem)
            return
        }
        await run(
            () => pokemonAPI.updateRound(round.id, {
                title: draft.title.trim() || undefined,
                rateMode: draft.rateMode,
                quotedRate: draft.quotedRate.trim() === "" ? undefined : Number(draft.quotedRate.trim()),
                bankAccount: draft.bankAccount.trim(),
                deadline: draft.deadline.trim() === "" ? null : draft.deadline,
                memo: draft.memo.trim() || undefined,
            }),
            "차수를 수정하지 못했습니다.",
        )
    }

    const addProduct = async () => {
        const problem = validateProductDraft(
            { ...productDraft, currency: round.currency, active: true }, [round.currency])
        if (problem !== null) {
            setError(problem)
            return
        }
        if (await run(
            () => pokemonAPI.createRoundProduct(round.id, {
                name: productDraft.name.trim(),
                currency: round.currency,
                price: parseAmount(productDraft.price) ?? 0,
                coins: parseCoins(productDraft.coins) ?? 0,
                active: true,
            }),
            "상품을 추가하지 못했습니다.",
        )) {
            setProductDraft({ name: "", price: "", coins: "" })
        }
    }

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            {header}

            {error !== null && (
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            )}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">차수 설정</CardTitle>
                    <p className="text-xs text-muted-foreground">
                        통화({round.currency})는 바꿀 수 없습니다 — 이미 그 통화 상품으로 채워진
                        신청서가 있습니다. 환율을 바꾸면 <b>앞으로 들어올 신청서에만</b> 적용되고,
                        이미 들어온 신청서는 각자의 환율을 유지합니다.
                    </p>
                </CardHeader>
                <CardContent className="grid gap-3 sm:grid-cols-2">
                    <div className="sm:col-span-2">
                        <Field label="제목">
                            <Input value={draft.title} maxLength={100}
                                   onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                                   placeholder={`${round.sequence}차 공동구매`} />
                        </Field>
                    </div>
                    <Field label="환율 방식">
                        <Select value={draft.rateMode}
                                onValueChange={(v) => setDraft({ ...draft, rateMode: v as PokemonRateMode })}>
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {(["FIXED", "PER_ORDER"] as const).map((m) => (
                                    <SelectItem key={m} value={m}>{RATE_MODE_LABELS[m]}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </Field>
                    <Field label={`환율 (지금 ${formatRate(currentRate.toKrw, round.currency)})`}>
                        <Input value={draft.quotedRate} inputMode="decimal"
                               disabled={draft.rateMode === "PER_ORDER"}
                               onChange={(e) => setDraft({ ...draft, quotedRate: e.target.value })} />
                    </Field>
                    <div className="sm:col-span-2">
                        <Field label="입금받을 계좌">
                            <Input value={draft.bankAccount} maxLength={200}
                                   onChange={(e) => setDraft({ ...draft, bankAccount: e.target.value })} />
                        </Field>
                    </div>
                    <Field label="마감일">
                        <Input type="date" value={draft.deadline}
                               onChange={(e) => setDraft({ ...draft, deadline: e.target.value })} />
                    </Field>
                    <div className="sm:col-span-2">
                        <Field label="메모">
                            <Textarea value={draft.memo} rows={2} maxLength={500}
                                      onChange={(e) => setDraft({ ...draft, memo: e.target.value })} />
                        </Field>
                    </div>
                    <div className="sm:col-span-2">
                        <Button onClick={() => void saveRound()} disabled={busy}>차수 저장</Button>
                    </div>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <CardTitle className="text-base">
                            이 차수의 상품 {products.length}개 ({round.currency})
                        </CardTitle>
                        <Button size="sm" variant="outline" disabled={busy}
                                onClick={() => void run(
                                    () => pokemonAPI.copyTemplateInto(round.id),
                                    "가져오지 못했습니다.")}>
                            <Copy className="mr-1 h-3 w-3" /> 내 상품표에서 가져오기
                        </Button>
                    </div>
                    <p className="text-xs text-muted-foreground">
                        이 차수에서만 쓰는 목록입니다. 고쳐도 다른 차수와 내 상품표는 그대로입니다.
                    </p>
                </CardHeader>
                <CardContent className="space-y-3">
                    <div className="grid gap-3 sm:grid-cols-4">
                        <div className="sm:col-span-2">
                            <Field label="이름">
                                <Input value={productDraft.name} maxLength={200}
                                       placeholder="예) GO패스 디럭스: 10월"
                                       onChange={(e) => setProductDraft({ ...productDraft, name: e.target.value })} />
                            </Field>
                        </div>
                        <Field label={`가격 (${round.currency})`}>
                            <Input value={productDraft.price} inputMode="decimal" placeholder="229.00"
                                   onChange={(e) => setProductDraft({ ...productDraft, price: e.target.value })} />
                        </Field>
                        <Field label="포켓코인 (패스는 0)">
                            <Input value={productDraft.coins} inputMode="numeric" placeholder="0"
                                   onChange={(e) => setProductDraft({ ...productDraft, coins: e.target.value })} />
                        </Field>
                        <div className="sm:col-span-4">
                            <Button onClick={() => void addProduct()} disabled={busy}>
                                <Plus className="mr-1 h-4 w-4" /> 이 차수에 추가
                            </Button>
                        </div>
                    </div>

                    {products.length === 0 && (
                        <p className="py-6 text-center text-sm text-muted-foreground">
                            이 차수에 등록된 상품이 없습니다. 신청 화면에 고를 것이 뜨지 않습니다.
                        </p>
                    )}

                    {products.map((product) => (
                        <div key={product.id}
                             className={`flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 ${
                                 product.active ? "" : "opacity-50"
                             }`}>
                            <div className="min-w-0">
                                <div className="flex flex-wrap items-center gap-2">
                                    <span className="font-medium">{product.name}</span>
                                    {!product.active && <Badge variant="outline">내림</Badge>}
                                    {product.inUse && <Badge variant="secondary">신청서에 사용됨</Badge>}
                                </div>
                                <div className="text-xs text-muted-foreground">
                                    {formatAmount(product.price, product.currency)}
                                    {product.coins > 0 && ` · ${formatCoins(product.coins)}`}
                                </div>
                            </div>
                            <div className="flex items-center gap-2">
                                <Button size="sm" variant="outline" disabled={busy}
                                        onClick={() => void run(
                                            () => pokemonAPI.updateProduct(product.id, {
                                                name: product.name,
                                                currency: product.currency,
                                                price: product.price,
                                                coins: product.coins,
                                                active: !product.active,
                                            }),
                                            "상태를 바꾸지 못했습니다.")}>
                                    {product.active ? "내리기" : "올리기"}
                                </Button>
                                <Button size="icon" variant="ghost"
                                        aria-label={`${product.name} 삭제`}
                                        disabled={busy || product.inUse}
                                        title={product.inUse ? "신청서에 쓰인 상품은 지울 수 없습니다" : undefined}
                                        onClick={() => void run(
                                            () => pokemonAPI.deleteProduct(product.id),
                                            "상품을 지우지 못했습니다.")}>
                                    <Trash2 className="h-3 w-3" />
                                </Button>
                            </div>
                        </div>
                    ))}
                </CardContent>
            </Card>
        </main>
    )
}

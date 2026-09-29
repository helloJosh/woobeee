"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw, Trash2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Field } from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    formatAmount,
    formatCoins,
    parseAmount,
    parseCoins,
    validateProductDraft,
    type PokemonHome,
    type PokemonManagedProduct,
    type PokemonProductDraft,
} from "@/lib/pokemon"

const emptyDraft = (currency: string): PokemonProductDraft =>
    ({ name: "", currency, price: "", coins: "", active: true })

/**
 * 주최자 설정과 상품표. 패스는 달마다 바뀌고("GO패스 디럭스: 9월") 스토어마다 통화도 가격도
 * 다르므로 인게임 상점을 보고 여기서 직접 넣는다. 상품표는 <b>주최자마다 따로</b>다.
 */
export default function PokemonProductsPage() {
    const [home, setHome] = useState<PokemonHome | null>(null)
    const [products, setProducts] = useState<PokemonManagedProduct[]>([])
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [bankAccount, setBankAccount] = useState("")
    const [draft, setDraft] = useState<PokemonProductDraft>(emptyDraft("INR"))
    const [editing, setEditing] = useState<number | null>(null)
    const [editDraft, setEditDraft] = useState<PokemonProductDraft>(emptyDraft("INR"))
    const [busy, setBusy] = useState(false)

    const load = useCallback(async () => {
        try {
            const loadedHome = await pokemonAPI.getHome()
            setHome(loadedHome)
            setBankAccount(loadedHome.myBankAccount ?? "")
            setDraft((d) => ({ ...d, currency: d.currency || loadedHome.currencies[0] || "INR" }))
            if (loadedHome.myHandle !== null) {
                setProducts(await pokemonAPI.getManagedProducts())
            }
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

    const currencies = home?.currencies ?? []

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

    const bodyOf = (d: PokemonProductDraft) => ({
        name: d.name.trim(),
        currency: d.currency,
        price: parseAmount(d.price) ?? 0,
        coins: parseCoins(d.coins) ?? 0,
        active: d.active,
    })

    const add = async () => {
        const problem = validateProductDraft(draft, currencies)
        if (problem !== null) {
            setError(problem)
            return
        }
        if (await run(() => pokemonAPI.createProduct(bodyOf(draft)), "상품을 등록하지 못했습니다.")) {
            setDraft(emptyDraft(draft.currency))
        }
    }

    const saveEdit = async (id: number) => {
        const problem = validateProductDraft(editDraft, currencies)
        if (problem !== null) {
            setError(problem)
            return
        }
        if (await run(() => pokemonAPI.updateProduct(id, bodyOf(editDraft)), "상품을 수정하지 못했습니다.")) {
            setEditing(null)
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

    if (loadState === "failed" || home === null) {
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
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 목록으로</Link>
                </Button>
            </main>
        )
    }

    const header = (
        <header className="space-y-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2">
                <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
            </Button>
            <h1 className="text-2xl font-bold">내 상품표</h1>
        </header>
    )

    if (home.myHandle === null) {
        return (
            <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
                {header}
                <Alert>
                    <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
                        <span>먼저 내 주소를 정해야 상품표를 만들 수 있습니다.</span>
                        <Button asChild size="sm">
                            <Link href="/pokemon/new">주소 정하기</Link>
                        </Button>
                    </AlertDescription>
                </Alert>
            </main>
        )
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
                    <CardTitle className="text-base">기본 입금 계좌</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                    <Field label="은행 · 계좌번호 · 예금주">
                        <Input
                            value={bankAccount}
                            onChange={(e) => setBankAccount(e.target.value)}
                            placeholder="예) 하나은행 12345678 홍길동"
                            maxLength={200}
                        />
                    </Field>
                    <p className="text-xs text-muted-foreground">
                        차수를 열 때 자동으로 채워집니다. 여기를 고쳐도 <b>이미 연 차수의 계좌는
                        바뀌지 않습니다</b> — 그 계좌로 이미 입금한 사람이 있을 수 있으니까요.
                    </p>
                    <Button
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                            void run(
                                () => pokemonAPI.updateHostSettings(bankAccount.trim()),
                                "계좌를 저장하지 못했습니다.",
                            )
                        }
                    >
                        계좌 저장
                    </Button>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">상품 추가</CardTitle>
                </CardHeader>
                <CardContent className="grid gap-3 sm:grid-cols-4">
                    <div className="sm:col-span-2">
                        <Field label="이름">
                            <Input
                                value={draft.name}
                                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                                placeholder="예) GO패스 디럭스: 11월"
                                maxLength={200}
                            />
                        </Field>
                    </div>
                    <Field label="통화">
                        <Select value={draft.currency}
                                onValueChange={(v) => setDraft({ ...draft, currency: v })}>
                            <SelectTrigger><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {currencies.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
                            </SelectContent>
                        </Select>
                    </Field>
                    <Field label="가격">
                        <Input
                            value={draft.price}
                            onChange={(e) => setDraft({ ...draft, price: e.target.value })}
                            inputMode="decimal"
                            placeholder="229.00"
                        />
                    </Field>
                    <div className="sm:col-span-2">
                        <Field label="포켓코인 (패스·티켓은 0)">
                            <Input
                                value={draft.coins}
                                onChange={(e) => setDraft({ ...draft, coins: e.target.value })}
                                inputMode="numeric"
                                placeholder="0"
                            />
                        </Field>
                    </div>
                    <div className="sm:col-span-4">
                        <Button onClick={() => void add()} disabled={busy} className="w-full sm:w-auto">
                            <Plus className="mr-1 h-4 w-4" /> 추가
                        </Button>
                    </div>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">상품 {products.length}개</CardTitle>
                    <p className="text-xs text-muted-foreground">
                        가격을 고쳐도 과거 신청서의 금액은 움직이지 않습니다 — 신청 당시 단가를 따로
                        들고 있습니다.
                    </p>
                </CardHeader>
                <CardContent className="space-y-2">
                    {products.length === 0 && (
                        <p className="py-6 text-center text-sm text-muted-foreground">
                            아직 등록한 상품이 없습니다.
                        </p>
                    )}
                    {products.map((product) =>
                        editing === product.id ? (
                            <div key={product.id} className="grid gap-3 rounded-md border p-3 sm:grid-cols-4">
                                <div className="sm:col-span-2">
                                    <Field label="이름">
                                        <Input value={editDraft.name} maxLength={200}
                                               onChange={(e) => setEditDraft({ ...editDraft, name: e.target.value })} />
                                    </Field>
                                </div>
                                <Field label="통화">
                                    <Select value={editDraft.currency}
                                            onValueChange={(v) => setEditDraft({ ...editDraft, currency: v })}>
                                        <SelectTrigger><SelectValue /></SelectTrigger>
                                        <SelectContent>
                                            {currencies.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
                                        </SelectContent>
                                    </Select>
                                </Field>
                                <Field label="가격">
                                    <Input value={editDraft.price} inputMode="decimal"
                                           onChange={(e) => setEditDraft({ ...editDraft, price: e.target.value })} />
                                </Field>
                                <div className="sm:col-span-2">
                                    <Field label="포켓코인">
                                        <Input value={editDraft.coins} inputMode="numeric"
                                               onChange={(e) => setEditDraft({ ...editDraft, coins: e.target.value })} />
                                    </Field>
                                </div>
                                <div className="flex gap-2 sm:col-span-4">
                                    <Button size="sm" onClick={() => void saveEdit(product.id)} disabled={busy}>
                                        저장
                                    </Button>
                                    <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>
                                        취소
                                    </Button>
                                </div>
                            </div>
                        ) : (
                            <div
                                key={product.id}
                                className={`flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 ${
                                    product.active ? "" : "opacity-50"
                                }`}
                            >
                                <div className="min-w-0">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <span className="font-medium">{product.name}</span>
                                        <Badge variant="outline">{product.currency}</Badge>
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
                                            onClick={() => {
                                                setEditing(product.id)
                                                setEditDraft({
                                                    name: product.name,
                                                    currency: product.currency,
                                                    price: String(product.price),
                                                    coins: String(product.coins),
                                                    active: product.active,
                                                })
                                            }}>
                                        수정
                                    </Button>
                                    <Button size="sm" variant="outline" disabled={busy}
                                            onClick={() =>
                                                void run(
                                                    () => pokemonAPI.updateProduct(product.id, {
                                                        name: product.name,
                                                        currency: product.currency,
                                                        price: product.price,
                                                        coins: product.coins,
                                                        active: !product.active,
                                                    }),
                                                    "상태를 바꾸지 못했습니다.",
                                                )
                                            }>
                                        {product.active ? "내리기" : "올리기"}
                                    </Button>
                                    <Button size="icon" variant="ghost"
                                            aria-label={`${product.name} 삭제`}
                                            disabled={busy || product.inUse}
                                            title={product.inUse ? "신청서에 쓰인 상품은 지울 수 없습니다" : undefined}
                                            onClick={() =>
                                                void run(() => pokemonAPI.deleteProduct(product.id),
                                                    "상품을 지우지 못했습니다.")
                                            }>
                                        <Trash2 className="h-3 w-3" />
                                    </Button>
                                </div>
                            </div>
                        ),
                    )}
                </CardContent>
            </Card>
        </main>
    )
}

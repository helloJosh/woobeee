"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw, Trash2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Field } from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    formatCoins,
    formatInr,
    parseCoins,
    parseInr,
    validateProductDraft,
    type PokemonManagedProduct,
    type PokemonProductDraft,
} from "@/lib/pokemon"

const EMPTY_DRAFT: PokemonProductDraft = { name: "", priceInr: "", coins: "", active: true }

/**
 * 운영자 상품 관리. 패스는 달마다 바뀌고("GO패스 디럭스: 9월") App Store 의 IAP 목록에는
 * 그 이름이 뜨지 않아 자동으로 가져올 수 없다 — 인게임 상점을 보고 여기서 직접 고친다.
 */
export default function PokemonProductsPage() {
    const [products, setProducts] = useState<PokemonManagedProduct[]>([])
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [draft, setDraft] = useState<PokemonProductDraft>(EMPTY_DRAFT)
    const [editing, setEditing] = useState<number | null>(null)
    const [editDraft, setEditDraft] = useState<PokemonProductDraft>(EMPTY_DRAFT)
    const [busy, setBusy] = useState(false)

    const load = useCallback(async () => {
        try {
            setProducts(await pokemonAPI.getManagedProducts())
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "상품 목록을 불러오지 못했습니다."))
        }
    }, [])

    useEffect(() => {
        void load()
    }, [load])

    const bodyOf = (d: PokemonProductDraft) => ({
        name: d.name.trim(),
        priceInr: parseInr(d.priceInr) ?? 0,
        coins: parseCoins(d.coins) ?? 0,
        active: d.active,
    })

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

    const add = async () => {
        const problem = validateProductDraft(draft)
        if (problem !== null) {
            setError(problem)
            return
        }
        if (await run(() => pokemonAPI.createProduct(bodyOf(draft)), "상품을 등록하지 못했습니다.")) {
            setDraft(EMPTY_DRAFT)
        }
    }

    const saveEdit = async (id: number) => {
        const problem = validateProductDraft(editDraft)
        if (problem !== null) {
            setError(problem)
            return
        }
        if (await run(() => pokemonAPI.updateProduct(id, bodyOf(editDraft)), "상품을 수정하지 못했습니다.")) {
            setEditing(null)
        }
    }

    const toggleActive = (product: PokemonManagedProduct) =>
        run(
            () =>
                pokemonAPI.updateProduct(product.id, {
                    name: product.name,
                    priceInr: product.priceInr,
                    coins: product.coins,
                    active: !product.active,
                }),
            "상태를 바꾸지 못했습니다.",
        )

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-3xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed") {
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
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록으로
                    </Link>
                </Button>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록
                    </Link>
                </Button>
                <h1 className="text-2xl font-bold">상품 관리</h1>
                <p className="text-xs text-muted-foreground">
                    인게임 상점을 보고 직접 넣고 고칩니다. 패스·티켓은 포켓코인 수를 0으로 둡니다.
                    가격을 고쳐도 <b>과거 신청서의 금액은 움직이지 않습니다</b> — 신청 당시 단가를
                    따로 들고 있습니다.
                </p>
            </header>

            {error !== null && (
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            )}

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
                    <Field label="루피">
                        <Input
                            value={draft.priceInr}
                            onChange={(e) => setDraft({ ...draft, priceInr: e.target.value })}
                            inputMode="decimal"
                            placeholder="229.00"
                        />
                    </Field>
                    <Field label="포켓코인 (패스는 0)">
                        <Input
                            value={draft.coins}
                            onChange={(e) => setDraft({ ...draft, coins: e.target.value })}
                            inputMode="numeric"
                            placeholder="0"
                        />
                    </Field>
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
                </CardHeader>
                <CardContent className="space-y-2">
                    {products.map((product) =>
                        editing === product.id ? (
                            <div key={product.id} className="grid gap-3 rounded-md border p-3 sm:grid-cols-4">
                                <div className="sm:col-span-2">
                                    <Field label="이름">
                                        <Input
                                            value={editDraft.name}
                                            onChange={(e) => setEditDraft({ ...editDraft, name: e.target.value })}
                                            maxLength={200}
                                        />
                                    </Field>
                                </div>
                                <Field label="루피">
                                    <Input
                                        value={editDraft.priceInr}
                                        onChange={(e) => setEditDraft({ ...editDraft, priceInr: e.target.value })}
                                        inputMode="decimal"
                                    />
                                </Field>
                                <Field label="포켓코인">
                                    <Input
                                        value={editDraft.coins}
                                        onChange={(e) => setEditDraft({ ...editDraft, coins: e.target.value })}
                                        inputMode="numeric"
                                    />
                                </Field>
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
                                        {!product.active && <Badge variant="outline">내림</Badge>}
                                        {product.inUse && <Badge variant="secondary">신청서에 사용됨</Badge>}
                                    </div>
                                    <div className="text-xs text-muted-foreground">
                                        {formatInr(product.priceInr)}
                                        {product.coins > 0 && ` · ${formatCoins(product.coins)}`}
                                    </div>
                                </div>
                                <div className="flex items-center gap-2">
                                    <Button
                                        size="sm"
                                        variant="outline"
                                        disabled={busy}
                                        onClick={() => {
                                            setEditing(product.id)
                                            setEditDraft({
                                                name: product.name,
                                                priceInr: String(product.priceInr),
                                                coins: String(product.coins),
                                                active: product.active,
                                            })
                                        }}
                                    >
                                        수정
                                    </Button>
                                    <Button
                                        size="sm"
                                        variant="outline"
                                        disabled={busy}
                                        onClick={() => void toggleActive(product)}
                                    >
                                        {product.active ? "내리기" : "올리기"}
                                    </Button>
                                    <Button
                                        size="icon"
                                        variant="ghost"
                                        aria-label={`${product.name} 삭제`}
                                        disabled={busy || product.inUse}
                                        title={product.inUse ? "신청서에 쓰인 상품은 지울 수 없습니다" : undefined}
                                        onClick={() =>
                                            void run(
                                                () => pokemonAPI.deleteProduct(product.id),
                                                "상품을 지우지 못했습니다.",
                                            )
                                        }
                                    >
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

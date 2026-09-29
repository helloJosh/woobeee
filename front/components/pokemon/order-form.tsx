"use client"

import { useMemo, useState } from "react"
import { AlertTriangle } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import ProductPicker from "@/components/pokemon/product-picker"
import { BankAccountLine, Field } from "@/components/pokemon/pokemon-bits"
import { describeGameApiError } from "@/lib/game-errors"
import {
    formatCoins,
    formatInr,
    formatKrw,
    formatRate,
    parseDonation,
    parseInr,
    quote,
    selectionLines,
    toKrw,
    validateOrderForm,
    type PokemonProduct,
    type PokemonSelection,
} from "@/lib/pokemon"

export interface OrderFormValues {
    selection: PokemonSelection
    applicantName: string
    depositorName: string
    donation: string
    extraInr: string
    memo: string
}

export const EMPTY_ORDER_FORM: OrderFormValues = {
    selection: {},
    applicantName: "",
    depositorName: "",
    donation: "",
    extraInr: "",
    memo: "",
}

export interface OrderFormBody {
    applicantName?: string
    depositorName?: string
    donationKrw: number
    extraInr: number
    memo?: string
    items: { productId: number; quantity: number }[]
}

/**
 * 신청서 작성·수정이 함께 쓰는 폼. 두 화면의 차이는 초기값과 저장 동작뿐이라 한 벌만 둔다.
 *
 * <p>금액 미리보기는 <b>현재 환율</b>로 계산한다 — 수정도 저장 시점 환율로 다시 잡히므로
 * 화면에 보인 금액과 저장되는 금액이 어긋나지 않는다.
 */
export default function OrderForm({
    products,
    rate,
    bankAccount,
    initial,
    loggedIn,
    memberName,
    nameLocked,
    submitLabel,
    droppedItems = [],
    onSubmit,
}: {
    products: PokemonProduct[]
    rate: number
    bankAccount: string
    initial: OrderFormValues
    loggedIn: boolean
    memberName: string | null
    /** 회원 신청서는 이름이 닉네임으로 고정된다 — 서버도 요청 값을 무시한다. */
    nameLocked: boolean
    submitLabel: string
    /** 상품표에서 내려가 더 이상 고를 수 없게 된 기존 항목 이름. */
    droppedItems?: string[]
    onSubmit: (body: OrderFormBody) => Promise<void>
}) {
    const [selection, setSelection] = useState<PokemonSelection>(initial.selection)
    const [applicantName, setApplicantName] = useState(initial.applicantName)
    const [depositorName, setDepositorName] = useState(initial.depositorName)
    const [donation, setDonation] = useState(initial.donation)
    const [extraInr, setExtraInr] = useState(initial.extraInr)
    const [memo, setMemo] = useState(initial.memo)
    const [error, setError] = useState<string | null>(null)
    const [saving, setSaving] = useState(false)

    const donationKrw = parseDonation(donation) ?? 0
    const extra = parseInr(extraInr) ?? 0
    const lines = useMemo(() => selectionLines(selection, products), [selection, products])
    const preview = useMemo(
        () => quote(selection, products, rate, donationKrw, extra),
        [selection, products, rate, donationKrw, extra],
    )

    const setQuantity = (productId: number, next: number) => {
        setSelection((current) => ({ ...current, [productId]: Math.max(0, Math.min(99, next)) }))
    }

    const save = async () => {
        const problem = validateOrderForm(
            { applicantName, depositorName, donation, extraInr, selection },
            loggedIn || nameLocked,
        )
        if (problem !== null) {
            setError(problem)
            return
        }

        setError(null)
        setSaving(true)
        try {
            await onSubmit({
                applicantName: applicantName.trim() || undefined,
                depositorName: depositorName.trim() || undefined,
                donationKrw,
                extraInr: extra,
                memo: memo.trim() || undefined,
                items: lines.map((line) => ({ productId: line.product.id, quantity: line.quantity })),
            })
        } catch (caught) {
            setError(describeGameApiError(caught, "저장하지 못했습니다."))
            setSaving(false)
        }
    }

    return (
        <>
            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">상품 고르기</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                    {droppedItems.length > 0 && (
                        <Alert variant="destructive">
                            <AlertTriangle className="h-4 w-4" />
                            <AlertDescription>
                                {droppedItems.join(", ")} 은(는) 스토어에서 내려가 다시 고를 수
                                없습니다. 이대로 저장하면 신청서에서 빠집니다.
                            </AlertDescription>
                        </Alert>
                    )}

                    <ProductPicker
                        products={products}
                        rate={rate}
                        selection={selection}
                        onChange={setQuantity}
                    />

                    <div className="rounded-md border p-3">
                        <Field label="상품표에 없는 것 — 루피 직접 입력 (선택)">
                            <Input
                                value={extraInr}
                                onChange={(event) => setExtraInr(event.target.value)}
                                inputMode="decimal"
                                placeholder="예) 249.00"
                            />
                        </Field>
                        <p className="mt-2 text-xs text-muted-foreground">
                            프리미엄 패스처럼 위 목록에 없는 것을 신청할 때 루피 금액을 적습니다.
                            무엇인지는 아래 메모에 남겨 주세요.
                            {extra > 0 && ` · ${formatInr(extra)} = ${formatKrw(toKrw(extra, rate))}`}
                        </p>
                    </div>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">신청자 정보</CardTitle>
                </CardHeader>
                <CardContent className="grid gap-3 sm:grid-cols-2">
                    {nameLocked ? (
                        <Field label="신청자">
                            <div className="flex h-10 items-center rounded-md border bg-muted px-3 text-sm">
                                {memberName ?? "로그인한 계정"}
                            </div>
                        </Field>
                    ) : (
                        <Field label="이름">
                            <Input
                                value={applicantName}
                                onChange={(event) => setApplicantName(event.target.value)}
                                placeholder="누구인지 알 수 있게 적어 주세요"
                                maxLength={60}
                            />
                        </Field>
                    )}
                    <Field label="입금자명 (다를 때만)">
                        <Input
                            value={depositorName}
                            onChange={(event) => setDepositorName(event.target.value)}
                            placeholder="통장에 찍히는 이름"
                            maxLength={60}
                        />
                    </Field>
                    <Field label="기부금 (선택, 원)">
                        <Input
                            value={donation}
                            onChange={(event) => setDonation(event.target.value)}
                            inputMode="numeric"
                            placeholder="0"
                        />
                    </Field>
                    <Field label="메모 (선택)">
                        <Textarea
                            value={memo}
                            onChange={(event) => setMemo(event.target.value)}
                            rows={2}
                            maxLength={500}
                            placeholder="전달할 말이 있으면"
                        />
                    </Field>
                </CardContent>
            </Card>

            <Card>
                <CardContent className="space-y-3 pt-6 text-sm">
                    <div className="flex justify-between">
                        <span className="text-muted-foreground">상품 합계</span>
                        <span>
                            {formatInr(preview.totalInr)} · {formatCoins(preview.totalCoins)}
                        </span>
                    </div>
                    {extra > 0 && (
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">└ 직접 입력한 루피</span>
                            <span>{formatInr(extra)}</span>
                        </div>
                    )}
                    <div className="flex justify-between">
                        <span className="text-muted-foreground">환산 ({formatRate(rate)})</span>
                        <span>{formatKrw(preview.itemsKrw)}</span>
                    </div>
                    <div className="flex justify-between">
                        <span className="text-muted-foreground">기부금</span>
                        <span>{formatKrw(preview.donationKrw)}</span>
                    </div>
                    <div className="flex justify-between border-t pt-3 text-base font-semibold">
                        <span>이체할 금액</span>
                        <span>{formatKrw(preview.transferKrw)}</span>
                    </div>
                    <BankAccountLine account={bankAccount} />

                    {error !== null && (
                        <Alert variant="destructive">
                            <AlertTriangle className="h-4 w-4" />
                            <AlertDescription>{error}</AlertDescription>
                        </Alert>
                    )}

                    <Button onClick={() => void save()} disabled={saving} className="w-full">
                        {saving ? "저장 중…" : submitLabel}
                    </Button>
                </CardContent>
            </Card>
        </>
    )
}

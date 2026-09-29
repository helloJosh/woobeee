"use client"

import { Minus, Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
    formatInr,
    formatKrw,
    pricePerCoin,
    toKrw,
    type PokemonProduct,
    type PokemonSelection,
} from "@/lib/pokemon"

/**
 * 상품 카드 그리드 — 스토어 이름, 루피, 원화 환산, 포켓코인당 단가(코인 상품만), 수량 조절.
 * 목록은 App Store 에서 매일 동기화되므로 개수도 구성도 고정이 아니다.
 */
export default function ProductPicker({
    products,
    rate,
    selection,
    onChange,
}: {
    products: PokemonProduct[]
    rate: number
    selection: PokemonSelection
    onChange: (productId: number, quantity: number) => void
}) {
    return (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {products.map((product) => {
                const quantity = selection[product.id] ?? 0
                const perCoin = pricePerCoin(product)

                return (
                    <div key={product.id} className="space-y-2 rounded-md border p-3">
                        <div className="flex items-baseline justify-between gap-2">
                            <span className="font-semibold">{product.name}</span>
                            <span className="text-sm">{formatInr(product.priceInr)}</span>
                        </div>
                        <div className="text-xs text-muted-foreground">
                            {formatKrw(toKrw(product.priceInr, rate))}
                            {perCoin !== null && ` · 포켓코인당 ${perCoin.toFixed(3)}루피`}
                        </div>

                        <div className="flex items-center gap-2">
                            <Button
                                size="icon"
                                variant="outline"
                                aria-label={`${product.name} 수량 줄이기`}
                                onClick={() => onChange(product.id, quantity - 1)}
                            >
                                <Minus className="h-3 w-3" />
                            </Button>
                            <span className="w-8 text-center text-sm font-medium">{quantity}</span>
                            <Button
                                size="icon"
                                variant="outline"
                                aria-label={`${product.name} 수량 늘리기`}
                                onClick={() => onChange(product.id, quantity + 1)}
                            >
                                <Plus className="h-3 w-3" />
                            </Button>
                        </div>
                    </div>
                )
            })}
        </div>
    )
}

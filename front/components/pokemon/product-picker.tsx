"use client"

import { Minus, Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
    formatAmount,
    formatCoins,
    formatKrw,
    pricePerCoin,
    toKrw,
    type PokemonProduct,
    type PokemonSelection,
} from "@/lib/pokemon"

/**
 * 상품 카드 그리드. 상품표는 그 차수 주최자의, 그 차수 통화의 것만 들어온다 —
 * 개수도 구성도 차수마다 다르다.
 */
export default function ProductPicker({
    products,
    rate,
    selection,
    onChange,
}: {
    products: PokemonProduct[]
    /** 이 차수에서 쓸 환율. FIXED 면 차수 환율이다. */
    rate: number
    selection: PokemonSelection
    onChange: (productId: number, quantity: number) => void
}) {
    if (products.length === 0) {
        return (
            <p className="rounded-md border p-4 text-center text-sm text-muted-foreground">
                이 통화로 등록된 상품이 없습니다. 주최자가 상품 관리에서 먼저 등록해야 합니다.
            </p>
        )
    }

    return (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {products.map((product) => {
                const quantity = selection[product.id] ?? 0
                const perCoin = pricePerCoin(product)

                return (
                    <div key={product.id} className="space-y-2 rounded-md border p-3">
                        <div className="flex items-baseline justify-between gap-2">
                            <span className="font-semibold">{product.name}</span>
                            <span className="text-sm">{formatAmount(product.price, product.currency)}</span>
                        </div>
                        <div className="text-xs text-muted-foreground">
                            {formatKrw(toKrw(product.price, rate))}
                            {product.coins > 0 && ` · ${formatCoins(product.coins)}`}
                            {perCoin !== null &&
                                ` · 코인당 ${formatAmount(Math.round(perCoin * 1000) / 1000, product.currency)}`}
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

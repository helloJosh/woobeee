"use client"

import Link from "next/link"
import { useCallback, useEffect, useMemo, useState } from "react"
import { ArrowLeft, Clock, Home, Loader2, ShoppingCart, Trash2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { cartAPI, productAPI } from "@/lib/api"
import { CartResponse, ProductSummary } from "@/lib/types"
import { useAuth } from "@/hooks/use-auth"

const formatPrice = (price: number) =>
  new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(price)

interface CartItem {
  cartProductId: number
  productId: number
  product: ProductSummary | null
}

const isLiveCart = (cart: CartResponse | null) =>
  !!cart && cart.status === "ACTIVE" && (cart.cartId ?? 0) > 0 && cart.products.length > 0

export default function CartPage() {
  const { loading: authLoading, isAuthenticated, memberId } = useAuth()
  const [cart, setCart] = useState<CartResponse | null>(null)
  const [items, setItems] = useState<CartItem[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [pendingProductId, setPendingProductId] = useState<number | null>(null)
  const [isClearing, setIsClearing] = useState(false)

  const enrich = useCallback(async (next: CartResponse) => {
    const enriched = await Promise.all(
      next.products.map(async (cartProduct): Promise<CartItem> => {
        try {
          const product = await productAPI.getProduct(cartProduct.productId)
          return { cartProductId: cartProduct.cartProductId, productId: cartProduct.productId, product }
        } catch {
          return { cartProductId: cartProduct.cartProductId, productId: cartProduct.productId, product: null }
        }
      })
    )
    setCart(next)
    setItems(enriched)
  }, [])

  const loadCart = useCallback(async () => {
    if (memberId == null) {
      return
    }
    setIsLoading(true)
    setError(null)
    try {
      const next = await cartAPI.getCart(memberId)
      await enrich(next)
    } catch {
      setCart(null)
      setItems([])
      setError("장바구니를 불러오지 못했습니다.")
    } finally {
      setIsLoading(false)
    }
  }, [enrich, memberId])

  useEffect(() => {
    if (authLoading) {
      return
    }
    if (!isAuthenticated || memberId == null) {
      setIsLoading(false)
      return
    }
    loadCart()
  }, [authLoading, isAuthenticated, memberId, loadCart])

  const totalPrice = useMemo(
    () => items.reduce((sum, item) => sum + (item.product?.price ?? 0), 0),
    [items]
  )

  const handleRemove = async (productId: number) => {
    if (!cart?.cartId || memberId == null) {
      return
    }
    setPendingProductId(productId)
    try {
      const next = await cartAPI.removeProduct(memberId, cart.cartId, productId)
      await enrich(next)
    } catch {
      // apiRequest가 사용자에게 alert로 안내한다.
    } finally {
      setPendingProductId(null)
    }
  }

  const handleClear = async () => {
    if (!cart?.cartId || memberId == null) {
      return
    }
    setIsClearing(true)
    try {
      const next = await cartAPI.clearCart(memberId, cart.cartId)
      setCart(next)
      setItems([])
    } catch {
      // apiRequest가 사용자에게 alert로 안내한다.
    } finally {
      setIsClearing(false)
    }
  }

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-30 border-b bg-background/95 backdrop-blur">
        <div className="flex h-16 items-center justify-between px-4">
          <Button asChild variant="ghost" size="sm" className="h-9 px-2.5 font-semibold">
            <Link href="/">
              <Home className="h-4 w-4" />
              HOME
            </Link>
          </Button>
          <div className="flex items-center gap-2 text-sm font-semibold">
            <ShoppingCart className="h-4 w-4" />
            장바구니
          </div>
          <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
            <Link href="/">
              <ArrowLeft className="h-4 w-4" />
              <span className="hidden sm:inline">쇼핑 계속하기</span>
            </Link>
          </Button>
        </div>
      </header>

      <section className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
        {authLoading || isLoading ? (
          <CartSkeleton />
        ) : !isAuthenticated || memberId == null ? (
          <EmptyState
            title="로그인이 필요합니다"
            description="장바구니를 보려면 로그인해 주세요."
            actionHref="/login"
            actionLabel="로그인"
          />
        ) : error ? (
          <div className="space-y-4">
            <div className="rounded-md border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
              {error}
            </div>
            <Button variant="outline" onClick={loadCart}>
              다시 시도
            </Button>
          </div>
        ) : items.length === 0 ? (
          <EmptyState
            title="장바구니가 비어있습니다"
            description="마음에 드는 작품을 담아보세요."
            actionHref="/"
            actionLabel="작품 둘러보기"
          />
        ) : (
          <div className="space-y-4">
            <LockTimer cart={cart} onExpire={loadCart} />

            <ul className="space-y-3">
              {items.map((item) => (
                <li
                  key={item.cartProductId}
                  className="flex gap-3 rounded-md border bg-background p-3"
                >
                  <div className="h-24 w-20 shrink-0 overflow-hidden rounded-md bg-muted">
                    {item.product?.thumbnailImageUrls?.[0] ?? item.product?.mainImageUrl ? (
                      <img
                        src={item.product?.thumbnailImageUrls?.[0] ?? item.product?.mainImageUrl ?? ""}
                        alt={item.product?.name ?? "상품 이미지"}
                        className="h-full w-full object-cover"
                        loading="lazy"
                      />
                    ) : (
                      <div className="flex h-full items-center justify-center text-center text-[11px] text-muted-foreground">
                        이미지 없음
                      </div>
                    )}
                  </div>

                  <div className="flex min-w-0 flex-1 flex-col justify-between">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold">
                        {item.product?.name ?? `상품 #${item.productId}`}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        {item.product?.artist ?? "작가 미상"}
                      </p>
                      {item.product ? (
                        <p className="truncate text-xs text-muted-foreground">
                          {item.product.width} x {item.product.height} · {item.product.material}
                        </p>
                      ) : (
                        <p className="truncate text-xs text-muted-foreground">
                          상품 정보를 불러오지 못했습니다.
                        </p>
                      )}
                    </div>
                    <p className="text-sm font-semibold">
                      {item.product ? formatPrice(item.product.price) : "-"}
                    </p>
                  </div>

                  <div className="flex items-start">
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 text-muted-foreground hover:text-destructive"
                      disabled={pendingProductId === item.productId}
                      onClick={() => handleRemove(item.productId)}
                      aria-label="상품 삭제"
                    >
                      {pendingProductId === item.productId ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <Trash2 className="h-4 w-4" />
                      )}
                    </Button>
                  </div>
                </li>
              ))}
            </ul>

            <div className="flex items-center justify-between rounded-md border bg-muted/30 px-4 py-3">
              <span className="text-sm text-muted-foreground">
                총 {items.length}점
              </span>
              <span className="text-base font-semibold">{formatPrice(totalPrice)}</span>
            </div>

            <div className="flex justify-end">
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button variant="outline" disabled={isClearing || !isLiveCart(cart)}>
                    {isClearing ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <Trash2 className="h-4 w-4" />
                    )}
                    장바구니 비우기
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>장바구니를 비울까요?</AlertDialogTitle>
                    <AlertDialogDescription>
                      담긴 상품이 모두 삭제되고 예약이 해제됩니다. 이 작업은 되돌릴 수 없습니다.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>취소</AlertDialogCancel>
                    <AlertDialogAction onClick={handleClear}>비우기</AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            </div>
          </div>
        )}
      </section>
    </main>
  )
}

function LockTimer({ cart, onExpire }: { cart: CartResponse | null; onExpire: () => void }) {
  const [remainingMs, setRemainingMs] = useState<number>(() =>
    cart ? new Date(cart.expiresAt).getTime() - Date.now() : 0
  )

  useEffect(() => {
    if (!cart) {
      return
    }
    const expiresAt = new Date(cart.expiresAt).getTime()
    const tick = () => setRemainingMs(expiresAt - Date.now())
    tick()
    const id = window.setInterval(tick, 1000)
    return () => window.clearInterval(id)
  }, [cart])

  useEffect(() => {
    if (cart && remainingMs <= 0) {
      onExpire()
    }
  }, [cart, remainingMs, onExpire])

  if (!cart) {
    return null
  }

  const expired = remainingMs <= 0
  const totalSeconds = Math.max(0, Math.floor(remainingMs / 1000))
  const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0")
  const seconds = String(totalSeconds % 60).padStart(2, "0")

  return (
    <div
      className={`flex items-center gap-2 rounded-md border px-4 py-3 text-sm ${
        expired
          ? "border-destructive/30 bg-destructive/5 text-destructive"
          : "border-amber-300/40 bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-300"
      }`}
    >
      <Clock className="h-4 w-4 shrink-0" />
      {expired ? (
        <span>예약이 만료되어 장바구니를 갱신합니다.</span>
      ) : (
        <span>
          예약 만료까지 <span className="font-semibold tabular-nums">{minutes}:{seconds}</span> 남음 ·
          담는 동안 다른 구매자가 가져갈 수 없어요.
        </span>
      )}
    </div>
  )
}

function CartSkeleton() {
  return (
    <div className="space-y-3">
      <div className="h-12 animate-pulse rounded-md bg-muted" />
      {Array.from({ length: 3 }).map((_, index) => (
        <div key={index} className="h-28 animate-pulse rounded-md bg-muted" />
      ))}
    </div>
  )
}

function EmptyState({
  title,
  description,
  actionHref,
  actionLabel,
}: {
  title: string
  description: string
  actionHref: string
  actionLabel: string
}) {
  return (
    <div className="flex min-h-[320px] flex-col items-center justify-center gap-4 rounded-md border bg-muted/30 px-6 text-center">
      <ShoppingCart className="h-10 w-10 text-muted-foreground" />
      <div className="space-y-1">
        <p className="text-base font-semibold">{title}</p>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      <Button asChild>
        <Link href={actionHref}>{actionLabel}</Link>
      </Button>
    </div>
  )
}

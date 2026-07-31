"use client"

import Link from "next/link"
import { useRouter } from "next/navigation"
import { useCallback, useEffect, useState } from "react"
import {
  ArrowLeft,
  Check,
  Home,
  Loader2,
  Lock,
  ShoppingCart,
  Zap,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import {
  Table,
  TableBody,
  TableCell,
  TableRow,
} from "@/components/ui/table"
import { cartAPI, productAPI } from "@/lib/api"
import { ProductSummary } from "@/lib/types"
import { useAuth } from "@/hooks/use-auth"

const formatPrice = (price: number) =>
  new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(price)

const STATUS_LABEL: Record<ProductSummary["status"], string> = {
  ACTIVE: "구매 가능",
  RESERVED: "예약 중",
  SOLD_OUT: "판매 완료",
  IMAGE_PENDING: "준비 중",
  IMAGE_FAILED: "준비 중",
}

const galleryImages = (product: ProductSummary): string[] => {
  const candidates = [
    product.mainImageUrl,
    ...(product.thumbnailImageUrls ?? []),
    ...(product.detailImageUrls ?? []),
  ].filter((url): url is string => !!url)
  return Array.from(new Set(candidates))
}

export default function ProductDetailPage({ params }: { params: { productId: string } }) {
  const productId = Number(params.productId)
  const router = useRouter()
  const { isAuthenticated, isSeller, memberId } = useAuth()

  const [product, setProduct] = useState<ProductSummary | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [activeImage, setActiveImage] = useState(0)
  const [adding, setAdding] = useState(false)
  const [added, setAdded] = useState(false)
  const [related, setRelated] = useState<ProductSummary[]>([])

  useEffect(() => {
    let ignore = false
    if (!Number.isFinite(productId)) {
      setNotFound(true)
      setIsLoading(false)
      return
    }
    setIsLoading(true)
    setNotFound(false)
    productAPI
      .getProduct(productId)
      .then((data) => {
        if (!ignore) {
          setProduct(data)
          setActiveImage(0)
        }
      })
      .catch(() => {
        if (!ignore) {
          setNotFound(true)
        }
      })
      .finally(() => {
        if (!ignore) {
          setIsLoading(false)
        }
      })
    return () => {
      ignore = true
    }
  }, [productId])

  // 같은 작가의 다른 작품 (작가가 없으면 최신 상품)
  useEffect(() => {
    let ignore = false
    if (!product) {
      return
    }
    productAPI
      .getProducts({ size: 12, artist: product.artist ?? undefined })
      .then((response) => {
        if (!ignore) {
          setRelated(response.contents.filter((item) => item.productId !== product.productId).slice(0, 4))
        }
      })
      .catch(() => {
        if (!ignore) {
          setRelated([])
        }
      })
    return () => {
      ignore = true
    }
  }, [product])

  const handleAddToCart = useCallback(async () => {
    if (adding || !product) {
      return
    }
    if (!isAuthenticated || memberId == null) {
      router.push("/login")
      return
    }
    setAdding(true)
    try {
      await cartAPI.addProduct(memberId, product.productId)
      setAdded(true)
      // 담은 뒤 상태를 RESERVED로 반영
      setProduct((prev) => (prev ? { ...prev, status: "RESERVED" } : prev))
      window.setTimeout(() => setAdded(false), 1500)
    } catch {
      // apiRequest가 사용자에게 alert로 안내한다.
    } finally {
      setAdding(false)
    }
  }, [adding, isAuthenticated, memberId, product, router])

  const handleBuyNow = () => {
    alert("바로 구매는 준비 중인 기능입니다. 장바구니 담기를 이용해 주세요.")
  }

  const images = product ? galleryImages(product) : []
  const isReserved = product?.status === "RESERVED"
  const isSoldOut = product?.status === "SOLD_OUT"
  const canBuy = product?.status === "ACTIVE"

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
          <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
            <Link href="/cart">
              <ShoppingCart className="h-4 w-4" />
              <span className="hidden sm:inline">장바구니</span>
            </Link>
          </Button>
        </div>
      </header>

      <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
        <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2 h-8 px-2 text-muted-foreground">
          <Link href="/">
            <ArrowLeft className="h-4 w-4" />
            목록으로
          </Link>
        </Button>

        {isLoading ? (
          <DetailSkeleton />
        ) : notFound || !product ? (
          <div className="flex min-h-[320px] flex-col items-center justify-center gap-4 rounded-md border bg-muted/30 px-6 text-center">
            <p className="text-base font-semibold">상품을 찾을 수 없습니다</p>
            <p className="text-sm text-muted-foreground">삭제되었거나 잘못된 주소일 수 있습니다.</p>
            <Button asChild>
              <Link href="/">작품 둘러보기</Link>
            </Button>
          </div>
        ) : (
          <div className="space-y-10">
            {/* 메인: 좌 이미지 / 우 정보 */}
            <section className="grid gap-6 md:grid-cols-2">
              <div className="space-y-3">
                <div className="aspect-square overflow-hidden rounded-md border bg-muted">
                  {images[activeImage] ? (
                    <img
                      src={images[activeImage]}
                      alt={product.name}
                      className="h-full w-full object-cover"
                    />
                  ) : (
                    <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                      이미지 없음
                    </div>
                  )}
                </div>
                {images.length > 1 && (
                  <div className="flex gap-2 overflow-x-auto">
                    {images.map((url, index) => (
                      <button
                        key={url}
                        type="button"
                        onClick={() => setActiveImage(index)}
                        className={`h-16 w-16 shrink-0 overflow-hidden rounded-md border transition-opacity ${
                          index === activeImage ? "ring-2 ring-ring" : "opacity-70 hover:opacity-100"
                        }`}
                        aria-label={`이미지 ${index + 1}`}
                      >
                        <img src={url} alt="" className="h-full w-full object-cover" />
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <div className="flex flex-col gap-4">
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <Badge variant={canBuy ? "secondary" : "outline"}>
                      {STATUS_LABEL[product.status]}
                    </Badge>
                    {isReserved && (
                      <span className="text-xs text-muted-foreground">다른 구매자가 담는 중입니다.</span>
                    )}
                  </div>
                  <h1 className="text-2xl font-semibold leading-tight">{product.name || "상품명 없음"}</h1>
                  <p className="text-sm text-muted-foreground">{product.artist ?? "작가 미상"}</p>
                </div>

                <p className="text-2xl font-bold">{formatPrice(product.price)}</p>

                {product.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {product.tags.map((tag) => (
                      <Badge key={tag} variant="outline" className="font-normal">
                        {tag}
                      </Badge>
                    ))}
                  </div>
                )}

                <Separator />

                <div className="flex items-start gap-2 rounded-md border border-amber-300/40 bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:bg-amber-950/30 dark:text-amber-300">
                  <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span>장바구니에 담으면 20분간 예약되어 다른 구매자가 가져갈 수 없습니다.</span>
                </div>

                <div className="mt-auto flex flex-col gap-2 sm:flex-row">
                  <Button
                    className="h-11 flex-1"
                    variant="outline"
                    disabled={adding || isSoldOut || (isReserved && !added)}
                    onClick={handleAddToCart}
                  >
                    {adding ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : added ? (
                      <Check className="h-4 w-4" />
                    ) : (
                      <ShoppingCart className="h-4 w-4" />
                    )}
                    {added ? "담았어요" : "장바구니 담기"}
                  </Button>
                  <Button
                    className="h-11 flex-1"
                    disabled={isSoldOut}
                    onClick={handleBuyNow}
                  >
                    <Zap className="h-4 w-4" />
                    바로 구매하기
                  </Button>
                </div>
                {isReserved && !added && (
                  <p className="text-xs text-muted-foreground">
                    현재 예약 중이라 담을 수 없습니다. 잠시 후 다시 시도해 주세요.
                  </p>
                )}
              </div>
            </section>

            {/* 상세 분류 표 */}
            <section className="space-y-3">
              <h2 className="text-lg font-semibold">상세 정보</h2>
              <div className="overflow-hidden rounded-md border">
                <Table>
                  <TableBody>
                    <SpecRow label="작가" value={product.artist ?? "작가 미상"} />
                    <SpecRow label="가격" value={formatPrice(product.price)} />
                    <SpecRow label="크기 (가로 x 세로)" value={`${product.width} x ${product.height}`} />
                    <SpecRow label="형태" value={product.shape} />
                    <SpecRow label="재료" value={product.material} />
                    <SpecRow label="태그" value={product.tags.length > 0 ? product.tags.join(", ") : "-"} />
                    <SpecRow label="등록일" value={new Date(product.createdAt).toLocaleDateString("ko-KR")} />
                  </TableBody>
                </Table>
              </div>
            </section>

            {/* 상세 설명 */}
            <section className="space-y-3">
              <h2 className="text-lg font-semibold">작품 설명</h2>
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground">
                {product.description || "등록된 설명이 없습니다."}
              </p>
            </section>

            {/* 상세 이미지 */}
            {product.detailImageUrls && product.detailImageUrls.length > 0 && (
              <section className="space-y-3">
                <h2 className="text-lg font-semibold">상세 이미지</h2>
                <div className="space-y-4">
                  {product.detailImageUrls.map((url, index) => (
                    <img
                      key={url}
                      src={url}
                      alt={`${product.name} 상세 이미지 ${index + 1}`}
                      className="w-full rounded-md border"
                      loading="lazy"
                    />
                  ))}
                </div>
              </section>
            )}

            {/* 같은 작가의 다른 작품 */}
            {related.length > 0 && (
              <section className="space-y-3">
                <h2 className="text-lg font-semibold">
                  {product.artist ? `${product.artist}의 다른 작품` : "다른 작품"}
                </h2>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  {related.map((item) => (
                    <RelatedCard key={item.productId} product={item} />
                  ))}
                </div>
              </section>
            )}
          </div>
        )}
      </div>
    </main>
  )
}

function SpecRow({ label, value }: { label: string; value: string }) {
  return (
    <TableRow>
      <TableCell className="w-40 bg-muted/40 font-medium text-muted-foreground">{label}</TableCell>
      <TableCell>{value}</TableCell>
    </TableRow>
  )
}

function RelatedCard({ product }: { product: ProductSummary }) {
  const src = product.thumbnailImageUrls?.[0] ?? product.mainImageUrl
  return (
    <Link
      href={`/products/${product.productId}`}
      className="group block overflow-hidden rounded-md border bg-background"
    >
      <div className="aspect-[4/5] bg-muted">
        {src ? (
          <img
            src={src}
            alt={product.name}
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
            loading="lazy"
          />
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
            이미지 없음
          </div>
        )}
      </div>
      <div className="space-y-1 p-2">
        <p className="truncate text-sm font-medium">{product.name || "상품명 없음"}</p>
        <p className="truncate text-xs text-muted-foreground">{formatPrice(product.price)}</p>
      </div>
    </Link>
  )
}

function DetailSkeleton() {
  return (
    <div className="grid gap-6 md:grid-cols-2">
      <div className="aspect-square animate-pulse rounded-md bg-muted" />
      <div className="space-y-4">
        <div className="h-6 w-3/4 animate-pulse rounded bg-muted" />
        <div className="h-4 w-1/3 animate-pulse rounded bg-muted" />
        <div className="h-8 w-1/2 animate-pulse rounded bg-muted" />
        <div className="h-11 w-full animate-pulse rounded bg-muted" />
      </div>
    </div>
  )
}

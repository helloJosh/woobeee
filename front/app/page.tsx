"use client"

import Link from "next/link"
import { FormEvent, useEffect, useMemo, useState } from "react"
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Home,
  Loader2,
  LogIn,
  LogOut,
  Moon,
  PackagePlus,
  Search,
  ShoppingCart,
  Newspaper,
  Sun,
  User,
  X,
} from "lucide-react"
import { useTheme } from "next-themes"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { cartAPI, productAPI } from "@/lib/api"
import { ProductSummary } from "@/lib/types"
import { useAuth } from "@/hooks/use-auth"

const formatPrice = (price: number) =>
  new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(price)

export default function HomePage() {
  const { loading: authLoading, isAuthenticated, isSeller } = useAuth()
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [products, setProducts] = useState<ProductSummary[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [keywordInput, setKeywordInput] = useState("")
  const [artistInput, setArtistInput] = useState("")
  const [tagInput, setTagInput] = useState("")
  const [keyword, setKeyword] = useState("")
  const [artist, setArtist] = useState("")
  const [tag, setTag] = useState("")

  const activeFilters = useMemo(
    () => [
      ...(keyword ? [{ type: "keyword" as const, label: `검색어 ${keyword}` }] : []),
      ...(artist ? [{ type: "artist" as const, label: `작가 ${artist}` }] : []),
      ...(tag ? [{ type: "tag" as const, label: `태그 ${tag}` }] : []),
    ],
    [artist, keyword, tag]
  )

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    let ignore = false

    const loadProducts = async () => {
      setIsLoading(true)
      setError(null)
      try {
        const response = await productAPI.getProducts({
          page,
          size: 16,
          q: keyword,
          artist,
          tag,
        })
        if (!ignore) {
          setProducts(response.contents)
          setHasNext(response.hasNext)
        }
      } catch {
        if (!ignore) {
          setProducts([])
          setHasNext(false)
          setError("상품 목록을 불러오지 못했습니다.")
        }
      } finally {
        if (!ignore) {
          setIsLoading(false)
        }
      }
    }

    loadProducts()

    return () => {
      ignore = true
    }
  }, [artist, keyword, page, tag])

  const submitSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setKeyword(keywordInput.trim())
    setArtist(artistInput.trim())
    setTag(tagInput.trim())
    setPage(0)
    setSearchOpen(false)
  }

  const clearFilter = (type: "keyword" | "artist" | "tag") => {
    if (type === "artist") {
      setArtist("")
      setArtistInput("")
    } else if (type === "keyword") {
      setKeyword("")
      setKeywordInput("")
    } else {
      setTag("")
      setTagInput("")
    }
    setPage(0)
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

          <nav className="flex min-w-0 items-center gap-1.5">
            <Button
              variant="ghost"
              size="sm"
              className="h-9 px-2.5"
              onClick={() => setSearchOpen((open) => !open)}
            >
              <Search className="h-4 w-4" />
              <span className="hidden sm:inline">검색</span>
            </Button>
            <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
              <Link href="/mypage">
                <User className="h-4 w-4" />
                <span className="hidden sm:inline">마이페이지</span>
              </Link>
            </Button>
            <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
              <Link href="/cart">
                <ShoppingCart className="h-4 w-4" />
                <span className="hidden sm:inline">장바구니</span>
              </Link>
            </Button>
            {isSeller ? (
              <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
                <Link href="/products/new">
                  <PackagePlus className="h-4 w-4" />
                  <span className="hidden sm:inline">상품등록</span>
                </Link>
              </Button>
            ) : null}
            <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
              <Link href="/blog">
                <Newspaper className="h-4 w-4" />
                <span className="hidden sm:inline">기술블로그</span>
              </Link>
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-9 px-2.5"
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            >
              {mounted && theme === "dark" ? (
                <Sun className="h-4 w-4" />
              ) : (
                <Moon className="h-4 w-4" />
              )}
              <span className="hidden sm:inline">
                {mounted && theme === "dark" ? "라이트모드" : "다크모드"}
              </span>
            </Button>
            {authLoading ? (
              <div className="h-9 w-16 rounded-md bg-muted" />
            ) : isAuthenticated ? (
              <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
                <Link href="/logout">
                  <LogOut className="h-4 w-4" />
                  <span className="hidden sm:inline">로그아웃</span>
                </Link>
              </Button>
            ) : (
              <Button asChild variant="ghost" size="sm" className="h-9 px-2.5">
                <Link href="/login">
                  <LogIn className="h-4 w-4" />
                  <span className="hidden sm:inline">로그인</span>
                </Link>
              </Button>
            )}
          </nav>
        </div>
      </header>

      <section className="border-b bg-muted/35">
        <div className="mx-auto max-w-7xl px-4 py-4 sm:px-6">
          <form onSubmit={submitSearch} className="space-y-3">
            <button
              type="button"
              onClick={() => setSearchOpen((open) => !open)}
              className="flex h-12 w-full items-center gap-3 rounded-md border bg-background px-4 text-left text-sm text-muted-foreground shadow-sm transition-colors hover:border-foreground/25 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Search className="h-4 w-4 shrink-0" />
              <span className="min-w-0 flex-1 truncate">
                {activeFilters.length > 0
                  ? activeFilters.map((filter) => filter.label).join(" · ")
                  : "검색어, 작가 또는 태그로 상품 검색"}
              </span>
            </button>

            {searchOpen && (
              <div className="grid gap-3 rounded-md border bg-background p-3 shadow-sm md:grid-cols-[1fr_1fr_1fr_auto]">
                <Input
                  value={keywordInput}
                  onChange={(event) => setKeywordInput(event.target.value)}
                  placeholder="검색어"
                  className="h-11"
                />
                <Input
                  value={artistInput}
                  onChange={(event) => setArtistInput(event.target.value)}
                  placeholder="작가 추가"
                  className="h-11"
                />
                <Input
                  value={tagInput}
                  onChange={(event) => setTagInput(event.target.value)}
                  placeholder="태그 추가"
                  className="h-11"
                />
                <Button type="submit" className="h-11 md:w-24">
                  <Search className="h-4 w-4" />
                  검색
                </Button>
              </div>
            )}
          </form>

          {activeFilters.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {activeFilters.map((filter) => (
                <button
                  key={filter.type}
                  type="button"
                  onClick={() => clearFilter(filter.type)}
                  className="inline-flex h-8 items-center gap-1.5 rounded-md border bg-background px-2.5 text-sm"
                >
                  {filter.label}
                  <X className="h-3.5 w-3.5" />
                </button>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        {error && (
          <div className="mb-4 rounded-md border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            {error}
          </div>
        )}

        <div className="grid min-h-[640px] grid-cols-2 items-start gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {isLoading
            ? Array.from({ length: 16 }).map((_, index) => (
                <div
                  key={index}
                  className="h-full min-h-[280px] animate-pulse rounded-md border bg-muted"
                />
              ))
            : products.map((product) => (
                <ProductCard key={product.productId} product={product} />
              ))}
        </div>

        {!isLoading && products.length === 0 && !error && (
          <div className="flex min-h-[320px] items-center justify-center rounded-md border bg-muted/30 text-sm text-muted-foreground">
            검색 결과가 없습니다.
          </div>
        )}

        <div className="mt-6 flex items-center justify-center gap-3">
          <Button
            variant="outline"
            disabled={page === 0 || isLoading}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            <ChevronLeft className="h-4 w-4" />
            이전
          </Button>
          <span className="flex h-10 min-w-16 items-center justify-center rounded-md border px-4 text-sm">
            {page + 1}
          </span>
          <Button
            variant="outline"
            disabled={!hasNext || isLoading}
            onClick={() => setPage((value) => value + 1)}
          >
            다음
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </section>
    </main>
  )
}

function ProductCard({ product }: { product: ProductSummary }) {
  const src = product.thumbnailImageUrls[0] ?? product.mainImageUrl
  const { isAuthenticated, isSeller, memberId } = useAuth()
  const [adding, setAdding] = useState(false)
  const [added, setAdded] = useState(false)

  const canAddToCart = isAuthenticated && !isSeller && memberId != null

  const handleAddToCart = async () => {
    if (memberId == null || adding) {
      return
    }
    setAdding(true)
    try {
      await cartAPI.addProduct(memberId, product.productId)
      setAdded(true)
      window.setTimeout(() => setAdded(false), 1500)
    } catch {
      // apiRequest가 사용자에게 alert로 안내한다.
    } finally {
      setAdding(false)
    }
  }

  return (
    <article className="group self-start overflow-hidden rounded-md border bg-background">
      <Link href={`/products/${product.productId}`} className="block">
        <div className="aspect-[4/5] bg-muted">
          {src ? (
            <img
              src={src}
              alt={`${product.artist ?? "작가"} 상품 이미지`}
              className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
              loading="lazy"
            />
          ) : (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              이미지 없음
            </div>
          )}
        </div>
        <div className="space-y-2 p-3">
          <div className="flex items-start justify-between gap-2">
            <p className="min-w-0 flex-1 truncate text-sm font-semibold">
              {product.name || "상품명 없음"}
            </p>
            <p className="shrink-0 text-sm font-semibold">
              {formatPrice(product.price)}
            </p>
          </div>
          <div className="min-w-0 space-y-1">
            <p className="truncate text-xs text-muted-foreground">
              {product.artist ?? "작가 미상"}
            </p>
            <p className="truncate text-xs text-muted-foreground">
              {product.width} x {product.height}
            </p>
            <p className="truncate text-xs text-muted-foreground">
              {product.material}
            </p>
          </div>
          <div className="flex h-7 gap-1 overflow-hidden">
            {product.tags.slice(0, 3).map((tag) => (
              <span
                key={tag}
                className="inline-flex h-7 max-w-24 items-center truncate rounded-md bg-secondary px-2 text-xs text-secondary-foreground"
              >
                {tag}
              </span>
            ))}
          </div>
        </div>
      </Link>
      {canAddToCart && (
        <div className="px-3 pb-3">
          <Button
            variant={added ? "secondary" : "outline"}
            size="sm"
            className="h-8 w-full"
            disabled={adding}
            onClick={handleAddToCart}
          >
            {adding ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : added ? (
              <Check className="h-4 w-4" />
            ) : (
              <ShoppingCart className="h-4 w-4" />
            )}
            {added ? "담음" : "담기"}
          </Button>
        </div>
      )}
    </article>
  )
}

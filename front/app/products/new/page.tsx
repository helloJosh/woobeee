"use client"

import { FormEvent, useEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ArrowLeft, Loader2, Upload } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { productAPI } from "@/lib/api"
import { useAuth } from "@/hooks/use-auth"

const PRODUCT_DRAFT_KEY = "product:create:draft"
const PRODUCT_DRAFT_TTL_MS = 60 * 60 * 1000

type ProductDraft = {
    savedAt: number
    values: {
        name: string
        description: string
        height: string
        width: string
        shape: string
        material: string
        price: string
        tagsText: string
    }
}

export default function NewProductPage() {
    const router = useRouter()
    const { loading, isAuthenticated, isSeller, memberId } = useAuth()
    const draftRestoredRef = useRef(false)
    const [name, setName] = useState("")
    const [description, setDescription] = useState("")
    const [height, setHeight] = useState("")
    const [width, setWidth] = useState("")
    const [shape, setShape] = useState("")
    const [material, setMaterial] = useState("")
    const [price, setPrice] = useState("")
    const [tagsText, setTagsText] = useState("")
    const [mainImage, setMainImage] = useState<File | null>(null)
    const [detailImages, setDetailImages] = useState<File[]>([])
    const [submitting, setSubmitting] = useState(false)
    const [message, setMessage] = useState<string | null>(null)

    const tags = useMemo(() => {
        return tagsText
            .split(",")
            .map((tag) => tag.trim())
            .filter(Boolean)
            .slice(0, 20)
    }, [tagsText])

    useEffect(() => {
        if (!loading && (!isAuthenticated || !isSeller)) {
            router.replace("/login")
        }
    }, [isAuthenticated, isSeller, loading, router])

    useEffect(() => {
        if (draftRestoredRef.current) {
            return
        }

        draftRestoredRef.current = true
        const rawDraft = localStorage.getItem(PRODUCT_DRAFT_KEY)
        if (!rawDraft) {
            return
        }

        try {
            const draft = JSON.parse(rawDraft) as ProductDraft
            if (Date.now() - draft.savedAt > PRODUCT_DRAFT_TTL_MS) {
                localStorage.removeItem(PRODUCT_DRAFT_KEY)
                return
            }

            setName(draft.values.name ?? "")
            setDescription(draft.values.description ?? "")
            setHeight(draft.values.height)
            setWidth(draft.values.width)
            setShape(draft.values.shape)
            setMaterial(draft.values.material)
            setPrice(draft.values.price)
            setTagsText(draft.values.tagsText)
        } catch {
            localStorage.removeItem(PRODUCT_DRAFT_KEY)
        }
    }, [])

    useEffect(() => {
        if (!draftRestoredRef.current) {
            return
        }

        const hasDraftValue = [name, description, height, width, shape, material, price, tagsText].some((value) => value.trim())
        if (!hasDraftValue) {
            localStorage.removeItem(PRODUCT_DRAFT_KEY)
            return
        }

        const draft: ProductDraft = {
            savedAt: Date.now(),
            values: {
                name,
                description,
                height,
                width,
                shape,
                material,
                price,
                tagsText,
            },
        }

        localStorage.setItem(PRODUCT_DRAFT_KEY, JSON.stringify(draft))
    }, [name, description, height, width, shape, material, price, tagsText])

    const uploadProductImage = async (file: File) => {
        const presigned = await productAPI.createImagePresignedUrl(file)
        await productAPI.uploadImage(file, presigned.uploadUrl)
        return presigned.fileKey
    }

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()

        if (!memberId) {
            setMessage("판매자 정보를 확인할 수 없습니다. 다시 로그인해주세요.")
            return
        }

        if (!mainImage) {
            setMessage("대표 이미지를 선택해주세요.")
            return
        }

        const numericPrice = Number(price)
        if (!Number.isFinite(numericPrice) || numericPrice <= 0) {
            setMessage("가격은 0보다 큰 숫자로 입력해주세요.")
            return
        }

        setSubmitting(true)
        setMessage(null)

        try {
            const mainImageKey = await uploadProductImage(mainImage)
            const detailImageKeys = await Promise.all(detailImages.map(uploadProductImage))
            const product = await productAPI.createProduct({
                sellerId: memberId,
                name,
                description,
                height,
                width,
                shape,
                material,
                tags,
                price: numericPrice,
                mainImageKey,
                detailImageKeys,
            })

            localStorage.removeItem(PRODUCT_DRAFT_KEY)
            router.replace(`/?productId=${product.productId}`)
        } catch (error) {
            console.error("Product create failed:", error)
            setMessage(error instanceof Error ? error.message : "상품 등록에 실패했습니다.")
        } finally {
            setSubmitting(false)
        }
    }

    if (loading || (!isAuthenticated || !isSeller)) {
        return (
            <main className="flex min-h-screen items-center justify-center gap-3 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                확인 중...
            </main>
        )
    }

    return (
        <main className="min-h-screen bg-background p-4">
            <form onSubmit={handleSubmit} className="mx-auto w-full max-w-2xl space-y-6 py-8">
                <Button variant="ghost" asChild className="self-start">
                    <Link href="/" className="flex items-center gap-2">
                        <ArrowLeft className="h-4 w-4" />
                        상품 목록으로 돌아가기
                    </Link>
                </Button>

                <div className="space-y-2">
                    <h1 className="text-2xl font-semibold">상품등록</h1>
                    <p className="text-sm text-muted-foreground">
                        작품 정보와 이미지를 등록합니다.
                    </p>
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-2 sm:col-span-2">
                        <Label htmlFor="name">상품명</Label>
                        <Input id="name" value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required />
                    </div>
                    <div className="space-y-2 sm:col-span-2">
                        <Label htmlFor="description">상품설명</Label>
                        <Textarea
                            id="description"
                            value={description}
                            onChange={(event) => setDescription(event.target.value)}
                            maxLength={2000}
                            required
                        />
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="height">높이</Label>
                        <Input id="height" value={height} onChange={(event) => setHeight(event.target.value)} maxLength={100} required />
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="width">너비</Label>
                        <Input id="width" value={width} onChange={(event) => setWidth(event.target.value)} maxLength={100} required />
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="shape">형태</Label>
                        <Input id="shape" value={shape} onChange={(event) => setShape(event.target.value)} maxLength={100} required />
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="material">재료</Label>
                        <Input id="material" value={material} onChange={(event) => setMaterial(event.target.value)} maxLength={200} required />
                    </div>
                </div>

                <div className="space-y-2">
                    <Label htmlFor="price">가격</Label>
                    <Input id="price" type="number" min="1" step="1" value={price} onChange={(event) => setPrice(event.target.value)} required />
                </div>

                <div className="space-y-2">
                    <Label htmlFor="tags">태그</Label>
                    <Input id="tags" value={tagsText} onChange={(event) => setTagsText(event.target.value)} placeholder="쉼표로 구분" />
                </div>

                <div className="space-y-2">
                    <Label htmlFor="mainImage">대표 이미지</Label>
                    <Input
                        id="mainImage"
                        type="file"
                        accept="image/*"
                        onChange={(event) => setMainImage(event.target.files?.[0] ?? null)}
                        required
                    />
                </div>

                <div className="space-y-2">
                    <Label htmlFor="detailImages">상세 이미지</Label>
                    <Input
                        id="detailImages"
                        type="file"
                        accept="image/*"
                        multiple
                        onChange={(event) => setDetailImages(Array.from(event.target.files ?? []).slice(0, 20))}
                    />
                </div>

                {message ? <p className="text-sm text-destructive">{message}</p> : null}

                <Button type="submit" className="w-full" disabled={submitting}>
                    {submitting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Upload className="mr-2 h-4 w-4" />}
                    상품 등록
                </Button>
            </form>
        </main>
    )
}

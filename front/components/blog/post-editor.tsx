"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { useTheme } from "next-themes"
import { useCreateBlockNote } from "@blocknote/react"
import { BlockNoteView } from "@blocknote/mantine"
import "@blocknote/core/fonts/inter.css"
import "@blocknote/mantine/style.css"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Progress } from "@/components/ui/progress"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select"
import { useCategories } from "@/hooks/use-categories"
import { postsAPI, tokenManager } from "@/lib/api"
import {
    buildPostFormData,
    canManagePosts,
    flattenCategories,
    resolvePendingImages,
    uniqueFileName,
    uploadProgressLabel,
    validatePostDraft,
    type PendingImage,
} from "@/lib/blog-admin"

interface PostEditorProps {
    postId?: number
}

/**
 * ADMIN 전용 노션풍 글 편집기. UI 게이팅일 뿐 진짜 방어는 서버 403이다.
 * 저장 계약은 기존 그대로 마크다운(multipart) — BlockNote 블록은 저장 직전에 변환한다.
 */
export default function PostEditor({ postId }: PostEditorProps) {
    const router = useRouter()
    const { resolvedTheme } = useTheme()
    const { categories } = useCategories()

    const [authorized, setAuthorized] = useState<boolean | null>(null)
    const [titleKo, setTitleKo] = useState("")
    const [titleEn, setTitleEn] = useState("")
    const [categoryId, setCategoryId] = useState<number | null>(null)
    const [loading, setLoading] = useState(Boolean(postId))
    const [saving, setSaving] = useState(false)
    const [errors, setErrors] = useState<string[]>([])
    const [progress, setProgress] = useState<{ loaded: number; total: number } | null>(null)

    // 드롭/붙여넣기된 이미지는 즉시 올리지 않고 여기 보관한다 — 글 생성 전에는 postId가
    // 없어 올릴 곳이 없다. 미리보기는 blob URL, 실제 전송은 저장 시 multipart로 간다.
    const pendingImagesRef = useRef<Map<string, PendingImage>>(new Map())

    const uploadFile = useCallback(async (file: File) => {
        const taken = new Set(
            [...pendingImagesRef.current.values()].map((image) => image.fileName),
        )
        const fileName = uniqueFileName(file.name || "image.png", taken)
        const localUrl = URL.createObjectURL(file)
        pendingImagesRef.current.set(localUrl, { localUrl, fileName, file })
        return localUrl
    }, [])

    const editorKo = useCreateBlockNote({ uploadFile })
    const editorEn = useCreateBlockNote({ uploadFile })

    useEffect(() => {
        const pendingImages = pendingImagesRef.current
        return () => {
            for (const image of pendingImages.values()) {
                URL.revokeObjectURL(image.localUrl)
            }
        }
    }, [])

    const categoryOptions = useMemo(() => flattenCategories(categories), [categories])

    useEffect(() => {
        const allowed = canManagePosts(tokenManager.getRole())
        setAuthorized(allowed)
        if (!allowed) {
            router.replace("/blog")
        }
    }, [router])

    useEffect(() => {
        if (!postId || authorized !== true) {
            return
        }

        let cancelled = false
        const load = async () => {
            try {
                const [ko, en] = await Promise.all([
                    postsAPI.getPostByLocale(postId, "ko-KR"),
                    postsAPI.getPostByLocale(postId, "en"),
                ])
                if (cancelled) return

                setTitleKo(ko.title ?? "")
                setTitleEn(en.title ?? "")
                setCategoryId(ko.categoryId ?? null)

                const koBlocks = await editorKo.tryParseMarkdownToBlocks(ko.content ?? "")
                editorKo.replaceBlocks(editorKo.document, koBlocks)
                const enBlocks = await editorEn.tryParseMarkdownToBlocks(en.content ?? "")
                editorEn.replaceBlocks(editorEn.document, enBlocks)
            } catch {
                if (!cancelled) {
                    setErrors(["글을 불러오지 못했습니다."])
                }
            } finally {
                if (!cancelled) {
                    setLoading(false)
                }
            }
        }
        load()
        return () => {
            cancelled = true
        }
    }, [postId, authorized, editorKo, editorEn])

    const handleSave = async () => {
        const markdownKo = await editorKo.blocksToMarkdownLossy(editorKo.document)
        const markdownEn = await editorEn.blocksToMarkdownLossy(editorEn.document)

        const resolved = resolvePendingImages(
            markdownKo.trim(),
            markdownEn.trim(),
            [...pendingImagesRef.current.values()],
        )

        const draft = {
            titleKo,
            titleEn,
            categoryId,
            markdownKo: resolved.markdownKo,
            markdownEn: resolved.markdownEn,
            attachments: resolved.attachments,
        }

        const validationErrors = validatePostDraft(draft)
        setErrors(validationErrors)
        if (validationErrors.length > 0) {
            return
        }

        setSaving(true)
        setProgress(null)
        const onProgress = (loaded: number, total: number) => setProgress({ loaded, total })
        try {
            const form = buildPostFormData(draft)
            if (postId) {
                await postsAPI.updatePost(postId, form, onProgress)
                router.push(`/blog/${postId}`)
            } else {
                await postsAPI.createPost(form, onProgress)
                router.push("/blog")
            }
        } catch (error) {
            setErrors([error instanceof Error ? error.message : "저장에 실패했습니다."])
            setSaving(false)
            setProgress(null)
        }
    }

    if (authorized !== true) {
        return null
    }

    return (
        <div className="max-w-4xl mx-auto p-6 space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-2xl font-bold">{postId ? "글 수정" : "새 글 작성"}</h1>
                <div className="flex gap-2">
                    <Button variant="outline" onClick={() => router.back()} disabled={saving}>
                        취소
                    </Button>
                    <Button onClick={handleSave} disabled={saving || loading}>
                        {saving ? "저장 중…" : "저장"}
                    </Button>
                </div>
            </div>

            {errors.length > 0 && (
                <ul className="text-sm text-destructive space-y-1">
                    {errors.map((error) => (
                        <li key={error}>{error}</li>
                    ))}
                </ul>
            )}

            {saving && progress && (
                <div className="space-y-1">
                    <Progress
                        value={progress.total > 0 ? Math.min(100, (progress.loaded / progress.total) * 100) : 0}
                    />
                    <p className="text-sm text-muted-foreground">
                        {uploadProgressLabel(progress.loaded, progress.total)}
                    </p>
                </div>
            )}

            <div className="flex gap-3">
                <Select
                    value={categoryId === null ? undefined : String(categoryId)}
                    onValueChange={(value) => setCategoryId(Number(value))}
                >
                    <SelectTrigger className="w-56">
                        <SelectValue placeholder="카테고리 선택" />
                    </SelectTrigger>
                    <SelectContent>
                        {categoryOptions.map((option) => (
                            <SelectItem key={option.id} value={String(option.id)}>
                                {option.label}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            </div>

            <Tabs defaultValue="ko">
                <TabsList>
                    <TabsTrigger value="ko">한국어</TabsTrigger>
                    <TabsTrigger value="en">English (선택)</TabsTrigger>
                </TabsList>

                <TabsContent value="ko" className="space-y-4">
                    <Input
                        placeholder="제목"
                        value={titleKo}
                        onChange={(event) => setTitleKo(event.target.value)}
                        className="text-lg font-semibold"
                    />
                    <div className="rounded-lg border min-h-[480px] py-4">
                        <BlockNoteView
                            editor={editorKo}
                            theme={resolvedTheme === "dark" ? "dark" : "light"}
                        />
                    </div>
                </TabsContent>

                <TabsContent value="en" className="space-y-4">
                    <Input
                        placeholder="Title (비우면 한국어 제목을 사용)"
                        value={titleEn}
                        onChange={(event) => setTitleEn(event.target.value)}
                        className="text-lg font-semibold"
                    />
                    <div className="rounded-lg border min-h-[480px] py-4">
                        <BlockNoteView
                            editor={editorEn}
                            theme={resolvedTheme === "dark" ? "dark" : "light"}
                        />
                    </div>
                </TabsContent>
            </Tabs>
        </div>
    )
}

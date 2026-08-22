"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useRouter } from "next/navigation"

import MarkdownView from "@/components/markdown-view"
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
import { AUTH_EXPIRED_MESSAGE, postsAPI, tokenManager } from "@/lib/api"
import {
    buildPostFormData,
    canManagePosts,
    collectDroppedImages,
    flattenCategories,
    insertSnippet,
    resolvePendingImages,
    toPlaceholderMarkdown,
    uploadProgressLabel,
    validatePostDraft,
    type PendingImage,
} from "@/lib/blog-admin"

interface PostEditorProps {
    postId?: number
}

interface MarkdownEditorPaneProps {
    value: string
    onValueChange: (value: string) => void
    registerImages: (files: File[]) => { images: PendingImage[]; snippet: string }
    placeholder: string
}

/**
 * IDE풍 분할 편집: 왼쪽 마크다운 원문, 오른쪽 게시 화면과 동일한 렌더(MarkdownView).
 * 이미지 파일을 textarea에 드롭/붙여넣으면 커서 위치에 마크다운 조각을 넣는다.
 */
function MarkdownEditorPane({
    value,
    onValueChange,
    registerImages,
    placeholder,
}: MarkdownEditorPaneProps) {
    const textareaRef = useRef<HTMLTextAreaElement>(null)

    const insertImages = (files: File[]): boolean => {
        const { images, snippet } = registerImages(files)
        if (images.length === 0) {
            return false
        }
        const textarea = textareaRef.current
        const start = textarea?.selectionStart ?? value.length
        const end = textarea?.selectionEnd ?? value.length
        const result = insertSnippet(value, start, end, snippet)
        onValueChange(result.text)
        // 제어 컴포넌트라 상태 반영 후에야 커서를 되돌릴 수 있다
        requestAnimationFrame(() => {
            textarea?.focus()
            textarea?.setSelectionRange(result.cursor, result.cursor)
        })
        return true
    }

    return (
        <div className="grid gap-4 lg:grid-cols-2">
            <textarea
                ref={textareaRef}
                value={value}
                onChange={(event) => onValueChange(event.target.value)}
                onDrop={(event) => {
                    // 이미지가 아닌 파일 드롭은 페이지 루트 가드가 삼킨다
                    if (insertImages([...event.dataTransfer.files])) {
                        event.preventDefault()
                    }
                }}
                onPaste={(event) => {
                    if (insertImages([...event.clipboardData.files])) {
                        event.preventDefault()
                    }
                }}
                placeholder={placeholder}
                spellCheck={false}
                className="min-h-[480px] w-full resize-y rounded-lg border bg-background p-4 font-mono text-sm leading-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
            <div className="min-h-[480px] overflow-auto rounded-lg border p-4">
                <MarkdownView content={value} />
            </div>
        </div>
    )
}

/**
 * ADMIN 전용 글 편집기. UI 게이팅일 뿐 진짜 방어는 서버 403이다.
 * 저장 계약은 기존 그대로 마크다운(multipart) — 원문을 직접 편집하므로 변환이 없다.
 */
export default function PostEditor({ postId }: PostEditorProps) {
    const router = useRouter()
    const { categories } = useCategories()

    const [authorized, setAuthorized] = useState<boolean | null>(null)
    const [titleKo, setTitleKo] = useState("")
    const [titleEn, setTitleEn] = useState("")
    const [categoryId, setCategoryId] = useState<number | null>(null)
    const [markdownKo, setMarkdownKo] = useState("")
    const [markdownEn, setMarkdownEn] = useState("")
    const [loading, setLoading] = useState(Boolean(postId))
    const [saving, setSaving] = useState(false)
    const [errors, setErrors] = useState<string[]>([])
    const [progress, setProgress] = useState<{ loaded: number; total: number } | null>(null)

    // 드롭/붙여넣기된 이미지는 즉시 올리지 않고 여기 보관한다 — 글 생성 전에는 postId가
    // 없어 올릴 곳이 없다. 미리보기는 blob URL, 실제 전송은 저장 시 multipart로 간다.
    const pendingImagesRef = useRef<Map<string, PendingImage>>(new Map())

    const registerImages = useCallback((files: File[]) => {
        const taken = new Set(
            [...pendingImagesRef.current.values()].map((image) => image.fileName),
        )
        const result = collectDroppedImages(files, taken, (file) => URL.createObjectURL(file))
        for (const image of result.images) {
            pendingImagesRef.current.set(image.localUrl, image)
        }
        return result
    }, [])

    // 에디터(textarea) 밖에 떨어진 파일 드롭은 브라우저가 이미지 문서로 이동해 버린다.
    // 페이지 루트에서 삼켜 내비게이션만 막는다 — textarea 안 드롭은 위에서 처리한다.
    const swallowStrayFileDrop = useCallback((event: React.DragEvent) => {
        if (event.dataTransfer.types.includes("Files")) {
            event.preventDefault()
        }
    }, [])

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
                // 조회 응답은 `${파일명}` 이 해석된 상태다. 되돌려 놓지 않으면 저장이
                // 해석된 경로를 원문에 구워 버린다(BLOG-AC-14/17).
                setMarkdownKo(toPlaceholderMarkdown(ko.content ?? "", postId))
                setMarkdownEn(toPlaceholderMarkdown(en.content ?? "", postId))
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
    }, [postId, authorized])

    const handleSave = async () => {
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
        <div
            className="w-full p-6 space-y-6"
            onDragOver={swallowStrayFileDrop}
            onDrop={swallowStrayFileDrop}
        >
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
                <div className="space-y-2">
                    <ul className="text-sm text-destructive space-y-1">
                        {errors.map((error) => (
                            <li key={error}>
                                {error === AUTH_EXPIRED_MESSAGE
                                    ? "인증이 만료되었습니다. 새 탭에서 다시 로그인한 뒤 이 화면으로 돌아와 저장을 다시 눌러 주세요 — 작성 중인 내용은 그대로 남아 있습니다."
                                    : error}
                            </li>
                        ))}
                    </ul>
                    {errors.includes(AUTH_EXPIRED_MESSAGE) && (
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => window.open("/login", "_blank")}
                        >
                            새 탭에서 다시 로그인
                        </Button>
                    )}
                </div>
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
                    <MarkdownEditorPane
                        value={markdownKo}
                        onValueChange={setMarkdownKo}
                        registerImages={registerImages}
                        placeholder="마크다운으로 작성하세요. 이미지는 드래그앤드롭 또는 붙여넣기."
                    />
                </TabsContent>

                <TabsContent value="en" className="space-y-4">
                    <Input
                        placeholder="Title (비우면 한국어 제목을 사용)"
                        value={titleEn}
                        onChange={(event) => setTitleEn(event.target.value)}
                        className="text-lg font-semibold"
                    />
                    <MarkdownEditorPane
                        value={markdownEn}
                        onValueChange={setMarkdownEn}
                        registerImages={registerImages}
                        placeholder="Write in Markdown. Drop or paste images."
                    />
                </TabsContent>
            </Tabs>
        </div>
    )
}

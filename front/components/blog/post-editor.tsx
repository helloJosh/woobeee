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
import { AUTH_EXPIRED_MESSAGE, postsAPI, tagsAPI, tokenManager } from "@/lib/api"
import {
    buildPostFormData,
    canManagePosts,
    normalizeTags,
    MAX_TAGS,
    MAX_TAG_LENGTH,
    MAX_DESCRIPTION,
    tagSuggestions,
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
    // 한 줄 설명 — 제목 아래에 나온다. 비우면 목록은 본문 요약으로 대체한다 (BLOG-AC-23)
    const [descriptionKo, setDescriptionKo] = useState("")
    const [descriptionEn, setDescriptionEn] = useState("")
    const [categoryId, setCategoryId] = useState<number | null>(null)
    // 태그 — chips 로 관리하고 저장 시 request JSON 에 실린다 (BLOG-AC-18)
    const [tags, setTags] = useState<string[]>([])
    const [tagDraft, setTagDraft] = useState("")
    // 기존 태그(인기순) — 자동완성으로 재사용을 유도한다. 없는 이름은 서버가 새로 만든다 (BLOG-AC-19)
    const [existingTags, setExistingTags] = useState<string[]>([])
    useEffect(() => {
        let cancelled = false
        tagsAPI.popular(200)
            .then((list) => { if (!cancelled) setExistingTags(list.map((t) => t.name)) })
            .catch(() => { if (!cancelled) setExistingTags([]) })
        return () => { cancelled = true }
    }, [])
    const suggestions = tagSuggestions(existingTags, tagDraft, tags)
    const addTag = (name: string) => {
        setTags(normalizeTags([...tags, name]))
        setTagDraft("")
    }
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
            router.replace("/")
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
                setDescriptionKo(ko.description ?? "")
                // 영어 응답은 한국어로 대체돼 올 수 있다 — 같은 값이면 영어 칸은 비워 둔다
                setDescriptionEn(en.description && en.description !== ko.description ? en.description : "")
                setCategoryId(ko.categoryId ?? null)
                setTags((ko.tags ?? []).map((t) => t.name))
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
            tags,
            descriptionKo,
            descriptionEn,
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
                router.push("/")
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

            <div className="flex flex-wrap gap-3">
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

                {/* 태그 입력: Enter 또는 쉼표로 추가, × 로 제거. 규칙은 normalizeTags/validatePostDraft (BLOG-AC-18) */}
                <div className="flex min-w-[16rem] flex-1 flex-wrap items-center gap-1.5 rounded-md border px-2 py-1">
                    {tags.map((t) => (
                        <span key={t.toLowerCase()} className="flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs">
                            #{t}
                            <button type="button" aria-label={`태그 ${t} 제거`} className="text-muted-foreground hover:text-foreground"
                                    onClick={() => setTags(tags.filter((x) => x !== t))}>×</button>
                        </span>
                    ))}
                    <div className="relative min-w-[8rem] flex-1">
                        <input
                            aria-label="태그 추가"
                            aria-autocomplete="list"
                            aria-expanded={suggestions.length > 0}
                            className="w-full bg-transparent px-1 py-1 text-sm outline-none"
                            placeholder={tags.length >= MAX_TAGS ? `태그는 ${MAX_TAGS}개까지` : "태그 입력 후 Enter — 기존 태그는 자동완성"}
                            value={tagDraft}
                            maxLength={MAX_TAG_LENGTH}
                            disabled={tags.length >= MAX_TAGS}
                            onChange={(e) => setTagDraft(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === "Enter" || e.key === ",") {
                                    e.preventDefault()
                                    // 입력과 대소문자만 다른 기존 태그가 있으면 그 표기를 쓴다 — 서버도 같은 태그로 본다
                                    const exact = suggestions.find((s) => s.toLowerCase() === tagDraft.trim().toLowerCase())
                                    addTag(exact ?? tagDraft)
                                } else if (e.key === "Backspace" && tagDraft === "" && tags.length > 0) {
                                    setTags(tags.slice(0, -1))
                                } else if (e.key === "Escape") {
                                    setTagDraft("")
                                }
                            }}
                            onBlur={() => { if (tagDraft.trim()) addTag(tagDraft) }}
                        />
                        {suggestions.length > 0 ? (
                            <ul role="listbox" aria-label="기존 태그"
                                className="absolute left-0 top-full z-20 mt-1 w-56 overflow-hidden rounded-md border bg-popover p-1 text-sm shadow-md">
                                {suggestions.map((s) => (
                                    <li key={s} role="option" aria-selected={false}>
                                        <button type="button" className="w-full rounded px-2 py-1 text-left hover:bg-muted"
                                                onMouseDown={(e) => e.preventDefault() /* input blur 전에 클릭을 받는다 */}
                                                onClick={() => addTag(s)}>
                                            #{s}
                                        </button>
                                    </li>
                                ))}
                                <li className="px-2 pt-1 text-[11px] text-muted-foreground">없는 이름은 Enter 로 새 태그가 됩니다</li>
                            </ul>
                        ) : null}
                    </div>
                    <span className="text-xs text-muted-foreground">{tags.length}/{MAX_TAGS}</span>
                </div>
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
                    <Input
                        placeholder="한 줄 설명 (선택, 300자) — 목록과 글 제목 아래에 나옵니다"
                        aria-label="설명"
                        value={descriptionKo}
                        maxLength={MAX_DESCRIPTION}
                        onChange={(event) => setDescriptionKo(event.target.value)}
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
                    <Input
                        placeholder="Description (optional, 300 chars — falls back to Korean)"
                        aria-label="Description"
                        value={descriptionEn}
                        maxLength={MAX_DESCRIPTION}
                        onChange={(event) => setDescriptionEn(event.target.value)}
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

// 블로그 글 관리(작성·수정)의 React-free 판단 로직.
// 서버의 진짜 방어는 app-mvc 필터의 ROLE_ADMIN 검사(403)이고, 여기는 UI 게이팅과
// multipart 조립을 맡는다. 계약: PostPostRequest(JSON) + markdownKr/markdownEn 파일 파트.

export const ADMIN_ROLE = "ROLE_ADMIN"

export interface PostDraft {
    titleKo: string
    titleEn: string
    categoryId: number | null
    markdownKo: string
    markdownEn: string
    attachments?: PendingImage[]
}

/**
 * 에디터에 드롭했지만 아직 서버로 가지 않은 이미지.
 * 미리보기는 blob URL로 그리고, 저장 시 본문의 blob URL을 ${fileName} 플레이스홀더로
 * 치환한 뒤 파일을 같은 multipart의 file 파트에 싣는다 — 서버가 {postId}/{fileName}으로
 * 올리고 조회 시 플레이스홀더를 공개 URL로 되돌린다.
 */
export interface PendingImage {
    localUrl: string
    fileName: string
    file: Blob
}

export const canManagePosts = (role: string | null | undefined): boolean =>
    role === ADMIN_ROLE

export const validatePostDraft = (draft: PostDraft): string[] => {
    const errors: string[] = []
    if (!draft.titleKo.trim()) {
        errors.push("제목을 입력해 주세요.")
    }
    if (draft.categoryId === null) {
        errors.push("카테고리를 선택해 주세요.")
    }
    if (!draft.markdownKo.trim()) {
        errors.push("본문을 입력해 주세요.")
    }
    return errors
}

export interface CategoryNode {
    id: number
    name: string
    count: number
    children?: CategoryNode[]
}

export interface CategoryOption {
    id: number
    label: string
}

export const flattenCategories = (
    categories: CategoryNode[],
    depth = 0,
): CategoryOption[] =>
    categories.flatMap((category) => [
        { id: category.id, label: `${"—".repeat(depth)}${depth > 0 ? " " : ""}${category.name}` },
        ...flattenCategories(category.children ?? [], depth + 1),
    ])

export const buildPostFormData = (draft: PostDraft): FormData => {
    const titleKo = draft.titleKo.trim()
    const titleEn = draft.titleEn.trim() || titleKo

    const form = new FormData()
    form.append(
        "request",
        new Blob(
            [JSON.stringify({ titleKo, titleEn, categoryId: draft.categoryId })],
            { type: "application/json" },
        ),
    )
    form.append(
        "markdownKr",
        new File([draft.markdownKo], "content-ko.md", { type: "text/markdown" }),
    )
    if (draft.markdownEn.trim()) {
        form.append(
            "markdownEn",
            new File([draft.markdownEn], "content-en.md", { type: "text/markdown" }),
        )
    }
    for (const image of draft.attachments ?? []) {
        form.append("file", new File([image.file], image.fileName, { type: image.file.type }))
    }
    return form
}

export const uniqueFileName = (originalName: string, taken: Set<string>): string => {
    const base = originalName.split("/").pop()!.trim().replace(/\s+/g, "-")
    if (!taken.has(base)) {
        return base
    }

    const dotIndex = base.lastIndexOf(".")
    const stem = dotIndex > 0 ? base.slice(0, dotIndex) : base
    const extension = dotIndex > 0 ? base.slice(dotIndex) : ""
    for (let suffix = 1; ; suffix++) {
        const candidate = `${stem}-${suffix}${extension}`
        if (!taken.has(candidate)) {
            return candidate
        }
    }
}

export const uploadProgressLabel = (loadedBytes: number, totalBytes: number): string => {
    if (totalBytes <= 0) {
        return "업로드 중…"
    }
    if (loadedBytes >= totalBytes) {
        // 업로드는 끝났고 서버가 S3 전송·DB 저장을 하는 구간 — 멈춘 게 아니다
        return "서버에서 처리 중…"
    }

    const toMegabytes = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)}MB`
    const percent = Math.round((loadedBytes / totalBytes) * 100)
    return `${toMegabytes(loadedBytes)} / ${toMegabytes(totalBytes)} (${percent}%)`
}

export const imageMarkdownSnippet = (fileName: string, url: string): string =>
    `![${fileName}](${url})`

/** [start, end) 선택 영역을 조각으로 대체하고 커서를 조각 뒤에 둔다. */
export const insertSnippet = (
    text: string,
    start: number,
    end: number,
    snippet: string,
): { text: string; cursor: number } => ({
    text: text.slice(0, start) + snippet + text.slice(end),
    cursor: start + snippet.length,
})

/**
 * 드롭/붙여넣기된 파일 중 이미지만 골라 보류 이미지로 등록할 목록과, 본문에 넣을
 * 마크다운 조각(문단 구분으로 연결)을 만든다. createUrl 은 URL.createObjectURL 주입점.
 */
export const collectDroppedImages = (
    files: File[],
    taken: Set<string>,
    createUrl: (file: File) => string,
): { images: PendingImage[]; snippet: string } => {
    const names = new Set(taken)
    const images: PendingImage[] = []
    for (const file of files) {
        if (!file.type.startsWith("image/")) {
            continue
        }
        const fileName = uniqueFileName(file.name || "image.png", names)
        names.add(fileName)
        images.push({ localUrl: createUrl(file), fileName, file })
    }
    return {
        images,
        snippet: images
            .map((image) => imageMarkdownSnippet(image.fileName, image.localUrl))
            .join("\n\n"),
    }
}

export const resolvePendingImages = (
    markdownKo: string,
    markdownEn: string,
    pending: PendingImage[],
): { markdownKo: string; markdownEn: string; attachments: PendingImage[] } => {
    let ko = markdownKo
    let en = markdownEn
    const attachments: PendingImage[] = []

    for (const image of pending) {
        if (!ko.includes(image.localUrl) && !en.includes(image.localUrl)) {
            continue
        }
        const placeholder = `\${${image.fileName}}`
        ko = ko.split(image.localUrl).join(placeholder)
        en = en.split(image.localUrl).join(placeholder)
        attachments.push(image)
    }

    return { markdownKo: ko, markdownEn: en, attachments }
}

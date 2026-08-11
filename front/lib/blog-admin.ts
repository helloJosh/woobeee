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

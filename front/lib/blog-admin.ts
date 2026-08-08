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
    return form
}

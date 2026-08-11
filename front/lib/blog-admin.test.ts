import { describe, expect, it } from "vitest"

import {
    buildPostFormData,
    canManagePosts,
    flattenCategories,
    resolvePendingImages,
    uniqueFileName,
    validatePostDraft,
    type PendingImage,
    type PostDraft,
} from "./blog-admin"

const draft = (overrides: Partial<PostDraft> = {}): PostDraft => ({
    titleKo: "제목",
    titleEn: "Title",
    categoryId: 3,
    markdownKo: "# 본문",
    markdownEn: "# Body",
    ...overrides,
})

describe("canManagePosts", () => {
    it("ROLE_ADMIN만 글 관리가 가능하다", () => {
        expect(canManagePosts("ROLE_ADMIN")).toBe(true)
    })

    it("ROLE_MEMBER·비로그인·이상값은 전부 불가다", () => {
        expect(canManagePosts("ROLE_MEMBER")).toBe(false)
        expect(canManagePosts(null)).toBe(false)
        expect(canManagePosts(undefined)).toBe(false)
        expect(canManagePosts("")).toBe(false)
        expect(canManagePosts("role_admin")).toBe(false)
    })
})

describe("validatePostDraft", () => {
    it("정상 초안은 오류가 없다", () => {
        expect(validatePostDraft(draft())).toEqual([])
    })

    it("한국어 제목이 비면 오류다", () => {
        expect(validatePostDraft(draft({ titleKo: "  " }))).toContain(
            "제목을 입력해 주세요.",
        )
    })

    it("카테고리가 없으면 오류다", () => {
        expect(validatePostDraft(draft({ categoryId: null }))).toContain(
            "카테고리를 선택해 주세요.",
        )
    })

    it("한국어 본문이 비면 오류다", () => {
        expect(validatePostDraft(draft({ markdownKo: "" }))).toContain(
            "본문을 입력해 주세요.",
        )
    })

    it("영어 제목·본문은 비어 있어도 된다", () => {
        expect(validatePostDraft(draft({ titleEn: "", markdownEn: "" }))).toEqual([])
    })
})

describe("flattenCategories", () => {
    it("트리를 깊이 들여쓰기 라벨의 평탄한 목록으로 만든다", () => {
        expect(
            flattenCategories([
                {
                    id: 1,
                    name: "개발",
                    count: 3,
                    children: [
                        { id: 2, name: "백엔드", count: 2 },
                        { id: 3, name: "프론트", count: 1, children: [{ id: 4, name: "React", count: 0 }] },
                    ],
                },
                { id: 5, name: "일상", count: 0 },
            ]),
        ).toEqual([
            { id: 1, label: "개발" },
            { id: 2, label: "— 백엔드" },
            { id: 3, label: "— 프론트" },
            { id: 4, label: "—— React" },
            { id: 5, label: "일상" },
        ])
    })

    it("빈 트리는 빈 목록이다", () => {
        expect(flattenCategories([])).toEqual([])
    })
})

describe("buildPostFormData", () => {
    it("request 파트에 제목·카테고리를 JSON으로 싣는다", async () => {
        const form = buildPostFormData(draft())

        const request = form.get("request") as Blob
        expect(request).toBeInstanceOf(Blob)
        expect(request.type).toBe("application/json")
        expect(JSON.parse(await request.text())).toEqual({
            titleKo: "제목",
            titleEn: "Title",
            categoryId: 3,
        })
    })

    it("영어 제목이 비면 한국어 제목으로 채운다", async () => {
        const form = buildPostFormData(draft({ titleEn: "  " }))

        const request = form.get("request") as Blob
        expect(JSON.parse(await request.text()).titleEn).toBe("제목")
    })

    it("마크다운 본문을 markdownKr/markdownEn 파일 파트로 싣는다", async () => {
        const form = buildPostFormData(draft())

        const kr = form.get("markdownKr") as File
        const en = form.get("markdownEn") as File
        expect(await kr.text()).toBe("# 본문")
        expect(await en.text()).toBe("# Body")
    })

    it("영어 본문이 비면 markdownEn 파트를 만들지 않는다", () => {
        const form = buildPostFormData(draft({ markdownEn: "" }))

        expect(form.get("markdownEn")).toBeNull()
        expect(form.get("markdownKr")).not.toBeNull()
    })

    it("첨부 이미지를 file 파트로 싣는다 — 서버 계약의 키는 {postId}/{파일명}", async () => {
        const attachments: PendingImage[] = [
            { localUrl: "blob:a", fileName: "cat.png", file: new Blob(["img-a"], { type: "image/png" }) },
            { localUrl: "blob:b", fileName: "dog.png", file: new Blob(["img-b"], { type: "image/png" }) },
        ]

        const form = buildPostFormData(draft({ attachments }))

        const files = form.getAll("file") as File[]
        expect(files.map((file) => file.name)).toEqual(["cat.png", "dog.png"])
        expect(await files[0].text()).toBe("img-a")
        expect(files[0].type).toBe("image/png")
    })

    it("첨부가 없으면 file 파트도 없다", () => {
        expect(buildPostFormData(draft()).getAll("file")).toEqual([])
    })
})

describe("uniqueFileName", () => {
    it("경로와 공백을 정리한다 — 공백이 남으면 마크다운 링크가 깨진다", () => {
        expect(uniqueFileName("dir/my photo.png", new Set())).toBe("my-photo.png")
    })

    it("이미 쓰인 이름이면 접미사로 유일하게 만든다", () => {
        const taken = new Set(["cat.png", "cat-1.png"])
        expect(uniqueFileName("cat.png", taken)).toBe("cat-2.png")
    })

    it("확장자가 없어도 동작한다", () => {
        expect(uniqueFileName("noext", new Set(["noext"]))).toBe("noext-1")
    })
})

describe("resolvePendingImages", () => {
    const pending: PendingImage[] = [
        { localUrl: "blob:http://x/aaa", fileName: "cat.png", file: new Blob(["a"]) },
        { localUrl: "blob:http://x/bbb", fileName: "dog.png", file: new Blob(["b"]) },
    ]

    it("본문의 blob URL을 ${파일명} 플레이스홀더로 치환한다", () => {
        const result = resolvePendingImages(
            "![고양이](blob:http://x/aaa)",
            "![cat](blob:http://x/aaa)",
            pending,
        )

        expect(result.markdownKo).toBe("![고양이](${cat.png})")
        expect(result.markdownEn).toBe("![cat](${cat.png})")
    })

    it("본문에서 실제로 쓰인 이미지만 첨부로 남긴다 — 드롭했다 지운 이미지는 안 보낸다", () => {
        const result = resolvePendingImages("![고양이](blob:http://x/aaa)", "", pending)

        expect(result.attachments.map((image) => image.fileName)).toEqual(["cat.png"])
    })

    it("보류 이미지가 없으면 본문을 그대로 둔다", () => {
        const result = resolvePendingImages("# 그대로", "", [])

        expect(result.markdownKo).toBe("# 그대로")
        expect(result.attachments).toEqual([])
    })
})

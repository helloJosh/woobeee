import { describe, expect, it } from "vitest"

import {
    buildPostFormData,
    canManagePosts,
    flattenCategories,
    validatePostDraft,
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
})

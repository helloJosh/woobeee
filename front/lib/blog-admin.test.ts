import { describe, expect, it } from "vitest"

import {
    buildPostFormData,
    canManagePosts,
    collectDroppedImages,
    flattenCategories,
    imageMarkdownSnippet,
    insertSnippet,
    resolvePendingImages,
    toPlaceholderMarkdown,
    type PendingImage,
    type PostDraft,
    uniqueFileName,
    uploadProgressLabel,
    validatePostDraft,
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

describe("uploadProgressLabel", () => {
    it("보낸 양 / 전체 양 (퍼센트) 형태로 만든다", () => {
        expect(uploadProgressLabel(500_000, 1_000_000)).toBe("0.5MB / 1.0MB (50%)")
    })

    it("퍼센트는 반올림한다", () => {
        expect(uploadProgressLabel(333_333, 1_000_000)).toBe("0.3MB / 1.0MB (33%)")
    })

    it("업로드가 끝나면 서버 처리 중임을 알린다 — 100%에서 멈춘 게 아니다", () => {
        expect(uploadProgressLabel(1_000_000, 1_000_000)).toBe("서버에서 처리 중…")
    })

    it("전체 크기를 모르면 일반 문구로 둔다", () => {
        expect(uploadProgressLabel(500_000, 0)).toBe("업로드 중…")
    })
})

describe("imageMarkdownSnippet", () => {
    it("파일명을 대체 텍스트로 하는 이미지 마크다운을 만든다", () => {
        expect(imageMarkdownSnippet("cat.png", "blob:http://x/aaa")).toBe(
            "![cat.png](blob:http://x/aaa)",
        )
    })
})

describe("insertSnippet", () => {
    it("커서 위치에 조각을 넣고 커서를 조각 뒤로 옮긴다", () => {
        expect(insertSnippet("한글 본문", 2, 2, "[X]")).toEqual({
            text: "한글[X] 본문",
            cursor: 5,
        })
    })

    it("선택 영역은 조각으로 대체한다", () => {
        expect(insertSnippet("abcdef", 1, 4, "-")).toEqual({ text: "a-ef", cursor: 2 })
    })
})

describe("collectDroppedImages", () => {
    const file = (name: string, type: string) => new File(["x"], name, { type })

    it("이미지 파일만 골라 고유 이름·로컬 URL로 등록하고 문단 조각을 만든다", () => {
        let seq = 0
        const result = collectDroppedImages(
            [file("cat.png", "image/png"), file("notes.txt", "text/plain"), file("cat.png", "image/png")],
            new Set(["cat.png"]),
            () => `blob:u${++seq}`,
        )

        expect(result.images.map((image) => image.fileName)).toEqual(["cat-1.png", "cat-2.png"])
        expect(result.images.map((image) => image.localUrl)).toEqual(["blob:u1", "blob:u2"])
        expect(result.snippet).toBe("![cat-1.png](blob:u1)\n\n![cat-2.png](blob:u2)")
    })

    it("이미지가 없으면 빈 결과다", () => {
        const result = collectDroppedImages([file("a.txt", "text/plain")], new Set(), () => "blob:x")

        expect(result.images).toEqual([])
        expect(result.snippet).toBe("")
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

describe("toPlaceholderMarkdown", () => {
    /**
     * BLOG-AC-17 — 수정 화면은 치환된 본문을 받아서 편집한다. 그대로 저장하면 해석된 경로가
     * 원문에 구워져 `${파일명}` 계약(BLOG-AC-14)이 깨진다. 불러올 때 되돌린다.
     */
    it("이 글의 이미지 경로를 플레이스홀더로 되돌린다", () => {
        expect(toPlaceholderMarkdown("![a](/api/back/posts/3/images/dropped.png)", 3)).toBe(
            "![a](${dropped.png})",
        )
    })

    it("퍼센트 인코딩된 한글 파일명을 디코딩해서 되돌린다", () => {
        expect(
            toPlaceholderMarkdown(
                "![a](/api/back/posts/13/images/%ED%95%9C%EA%B8%80-%EA%B7%B8%EB%A6%BC.png)",
                13,
            ),
        ).toBe("![a](${한글-그림.png})")
    })

    it("%20 은 공백으로 되돌린다 — 다시 저장하면 같은 키로 인코딩된다", () => {
        expect(toPlaceholderMarkdown("![a](/api/back/posts/3/images/a%20b.png)", 3)).toBe(
            "![a](${a b.png})",
        )
    })

    it("이미지 여러 개를 모두 되돌린다", () => {
        expect(
            toPlaceholderMarkdown(
                "![a](/api/back/posts/3/images/one.png)\n\n![b](/api/back/posts/3/images/two.png)",
                3,
            ),
        ).toBe("![a](${one.png})\n\n![b](${two.png})")
    })

    /**
     * 다른 글의 이미지 경로는 건드리지 않는다. 플레이스홀더는 저장 시 **이 글의** prefix 로
     * 해석되므로, 남의 글 경로를 되돌리면 존재하지 않는 오브젝트를 가리키게 된다.
     */
    it("다른 postId 의 이미지 경로는 그대로 둔다", () => {
        const content = "![a](/api/back/posts/99/images/other.png)"

        expect(toPlaceholderMarkdown(content, 3)).toBe(content)
    })

    it("외부 이미지 URL 은 건드리지 않는다", () => {
        const content = "![a](https://example.com/logo.png)\n![b](/static/hero.png)"

        expect(toPlaceholderMarkdown(content, 3)).toBe(content)
    })

    it("이미 플레이스홀더인 본문은 그대로 둔다 — 두 번 적용해도 안전하다", () => {
        const content = "![a](${dropped.png})"

        expect(toPlaceholderMarkdown(content, 3)).toBe(content)
        expect(toPlaceholderMarkdown(toPlaceholderMarkdown(content, 3), 3)).toBe(content)
    })

    it("빈 본문과 이미지 없는 본문을 그대로 돌려준다", () => {
        expect(toPlaceholderMarkdown("", 3)).toBe("")
        expect(toPlaceholderMarkdown("# 제목\n\n본문뿐", 3)).toBe("# 제목\n\n본문뿐")
    })

    /**
     * 디코딩할 수 없는 시퀀스는 원문을 남긴다. decodeURIComponent 가 던지면 편집기가 글을
     * 아예 못 여는데, 그건 이 되돌리기가 막으려는 문제보다 나쁘다.
     */
    it("깨진 퍼센트 인코딩은 되돌리지 않고 원문을 남긴다", () => {
        const content = "![a](/api/back/posts/3/images/%E0%A4%A.png)"

        expect(toPlaceholderMarkdown(content, 3)).toBe(content)
    })
})

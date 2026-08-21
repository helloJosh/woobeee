import { describe, expect, it } from "vitest"

import { markdownUrlTransform } from "./markdown-url"

describe("markdownUrlTransform", () => {
    it("blob URL을 통과시킨다 — 편집기 미리보기의 드롭 이미지가 이것이다", () => {
        expect(markdownUrlTransform("blob:https://www.woobeee.com/36a54f18")).toBe(
            "blob:https://www.woobeee.com/36a54f18",
        )
    })

    it("일반 http(s)·상대 경로는 기본 동작대로 통과시킨다", () => {
        expect(markdownUrlTransform("https://example.com/a.png")).toBe(
            "https://example.com/a.png",
        )
        expect(markdownUrlTransform("/images/a.png")).toBe("/images/a.png")
    })

    it("위험한 프로토콜은 여전히 빈 문자열로 걸러낸다", () => {
        expect(markdownUrlTransform("javascript:alert(1)")).toBe("")
        expect(markdownUrlTransform("data:text/html,x")).toBe("")
    })
})

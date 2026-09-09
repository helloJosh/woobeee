import { describe, expect, it } from "vitest"
import { excerpt, firstImageUrl, placeholderHue } from "./post-preview"

// 홈 카드의 썸네일·요약은 본문 마크다운에서 만든다 — 글에 전용 필드가 없다.
describe("firstImageUrl", () => {
    it("마크다운 이미지 문법의 첫 URL 을 돌려준다", () => {
        const md = "# 제목\n\n본문 ![다이어그램](https://img.example.com/a.png) 그리고 ![둘째](https://img.example.com/b.png)"
        expect(firstImageUrl(md)).toBe("https://img.example.com/a.png")
    })

    it("HTML img 태그도 인식하고, 앞에 나온 쪽을 고른다", () => {
        expect(firstImageUrl('<p><img src="/api/back/posts/3/images/x.png" alt=""></p> ![md](https://x/y.png)'))
            .toBe("/api/back/posts/3/images/x.png")
        expect(firstImageUrl("![md](https://x/y.png) <img src='/z.png'>")).toBe("https://x/y.png")
    })

    it("마크다운 이미지의 title 부분은 URL 에서 뺀다", () => {
        expect(firstImageUrl('![a](https://x/y.png "제목")')).toBe("https://x/y.png")
    })

    it("이미지가 없으면 null", () => {
        expect(firstImageUrl("본문만 [링크](https://x) 있음")).toBeNull()
        expect(firstImageUrl("")).toBeNull()
        expect(firstImageUrl(null)).toBeNull()
    })
})

describe("excerpt", () => {
    it("헤딩·강조·링크·이미지·코드블록·HTML 을 걷어내고 본문 텍스트만 남긴다", () => {
        const md = [
            "# 제목은 빠진다",
            "",
            "**굵게** 쓴 _첫_ 문장. [링크 텍스트](https://x) 유지.",
            "![그림](https://x/a.png)",
            "```java",
            "System.out.println(\"코드는 빠진다\");",
            "```",
            "> 인용도 텍스트로.",
            "<br/>둘째 문단 `인라인 코드`",
        ].join("\n")
        expect(excerpt(md, 200)).toBe("굵게 쓴 첫 문장. 링크 텍스트 유지. 인용도 텍스트로. 둘째 문단 인라인 코드")
    })

    it("최대 길이를 넘으면 잘라 말줄임표를 붙인다", () => {
        expect(excerpt("가".repeat(50), 10)).toBe("가".repeat(10) + "…")
        expect(excerpt("짧다", 10)).toBe("짧다")
    })

    it("빈 본문은 빈 문자열", () => {
        expect(excerpt("", 100)).toBe("")
        expect(excerpt(null, 100)).toBe("")
        expect(excerpt("![only](https://x/a.png)", 100)).toBe("")
    })
})

describe("placeholderHue", () => {
    it("같은 이름은 같은 색, 다른 이름은 (대체로) 다른 색, 항상 0~359", () => {
        expect(placeholderHue("백엔드")).toBe(placeholderHue("백엔드"))
        expect(placeholderHue("백엔드")).not.toBe(placeholderHue("프론트엔드"))
        for (const name of ["", "a", "인프라", "알고리즘", "회고", "기타"]) {
            const h = placeholderHue(name)
            expect(h).toBeGreaterThanOrEqual(0)
            expect(h).toBeLessThan(360)
            expect(Number.isInteger(h)).toBe(true)
        }
    })
})

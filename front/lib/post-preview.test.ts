import { describe, expect, it } from "vitest"
import { excerpt, summaryOf } from "./post-preview"

// 홈 목록의 요약은 본문 마크다운에서 만든다 — 글에 전용 필드가 없다.
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

// BLOG-AC-23 — 목록의 제목 아래 줄: 작성자가 적은 설명이 있으면 그것, 없으면 본문 요약
describe("summaryOf", () => {
    it("설명이 있으면 설명을 그대로, 없거나 비면 본문 요약", () => {
        expect(summaryOf("직접 쓴 설명", "# 제목\n\n본문 첫 문장.", 100)).toBe("직접 쓴 설명")
        expect(summaryOf(null, "# 제목\n\n본문 첫 문장.", 100)).toBe("본문 첫 문장.")
        expect(summaryOf("   ", "본문", 100)).toBe("본문")
        expect(summaryOf(undefined, "", 100)).toBe("")
    })
})

import { describe, expect, it } from "vitest"

import { preserveBlankLines } from "./markdown-blank-lines"

describe("preserveBlankLines", () => {
    it("빈 줄 하나(문단 구분)는 그대로 둔다", () => {
        expect(preserveBlankLines("가\n\n나")).toBe("가\n\n나")
    })

    it("단일 개행과 일반 본문은 그대로다", () => {
        expect(preserveBlankLines("가\n나")).toBe("가\n나")
    })

    it("연속 빈 줄은 여분 하나당 &nbsp; 문단으로 살린다 — 마크다운은 이를 하나로 접는다", () => {
        expect(preserveBlankLines("가\n\n\n나")).toBe("가\n\n&nbsp;\n\n나")
        expect(preserveBlankLines("가\n\n\n\n나")).toBe("가\n\n&nbsp;\n\n&nbsp;\n\n나")
    })

    it("코드 펜스 안의 빈 줄은 건드리지 않는다", () => {
        const fenced = "```sql\nSELECT 1;\n\n\n\nSELECT 2;\n```"
        expect(preserveBlankLines(fenced)).toBe(fenced)
    })

    it("펜스 앞뒤의 연속 빈 줄은 여전히 살린다", () => {
        expect(preserveBlankLines("가\n\n\n```\ncode\n```")).toBe(
            "가\n\n&nbsp;\n\n```\ncode\n```",
        )
    })

    it("공백만 있는 줄도 빈 줄로 취급한다", () => {
        expect(preserveBlankLines("가\n  \n\t\n나")).toBe("가\n\n&nbsp;\n\n나")
    })
})

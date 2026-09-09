import { describe, expect, it } from "vitest"
import { blogRedirectTarget } from "./blog-redirect"

// /blog 는 / 로 옮겼다 — 옛 링크의 필터 쿼리를 잃지 않게 옮긴다.
describe("blogRedirectTarget", () => {
    it("쿼리가 없으면 홈", () => {
        expect(blogRedirectTarget({})).toBe("/")
        expect(blogRedirectTarget(new URLSearchParams())).toBe("/")
    })

    it("category·search·tag 만 옮기고 나머지는 버린다", () => {
        expect(blogRedirectTarget({ category: "3", search: "카프카", tag: "Spring", categoryName: "백엔드", utm: "x" }))
            .toBe("/?category=3&search=%EC%B9%B4%ED%94%84%EC%B9%B4&tag=Spring")
    })

    it("배열로 들어온 값은 첫 값만, 빈 값은 건너뛴다", () => {
        expect(blogRedirectTarget({ category: ["7", "8"], search: "" })).toBe("/?category=7")
    })
})

import { describe, expect, it } from "vitest"
import { blogFilterTarget } from "./blog-redirect"

// 블로그가 /blog 로 돌아갔다(홈은 공동구매다) — 옛 `/?category=3` 링크의 필터를 잃지 않게 옮긴다.
describe("blogFilterTarget", () => {
    it("옮길 필터가 없으면 null — 그냥 홈에 들어온 사람은 홈에 둔다", () => {
        expect(blogFilterTarget({})).toBeNull()
        expect(blogFilterTarget(new URLSearchParams())).toBeNull()
        expect(blogFilterTarget({ utm: "x" })).toBeNull()
    })

    it("category·search·tag 만 옮기고 나머지는 버린다", () => {
        expect(blogFilterTarget({ category: "3", search: "카프카", tag: "Spring", categoryName: "백엔드", utm: "x" }))
            .toBe("/blog?category=3&search=%EC%B9%B4%ED%94%84%EC%B9%B4&tag=Spring")
    })

    it("배열로 들어온 값은 첫 값만, 빈 값은 건너뛴다", () => {
        expect(blogFilterTarget({ category: ["7", "8"], search: "" })).toBe("/blog?category=7")
    })
})

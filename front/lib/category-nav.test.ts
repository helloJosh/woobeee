import { describe, expect, it } from "vitest"
import { categoryNav } from "./category-nav"
import type { Category } from "./types"

const categories: Category[] = [
    { id: 1, name: "백엔드", count: 5, children: [
        { id: 11, name: "Spring", count: 3 },
        { id: 12, name: "JPA", count: 2 },
    ] },
    { id: 2, name: "프론트엔드", count: 1 },
    { id: 3, name: "회고", count: 0, children: [] },
]

// 우아한 스타일 상단 내비: 부모 chips 한 줄, 고른 부모의 자식 chips 한 줄
describe("categoryNav", () => {
    it("선택이 없으면 부모만, 자식 줄은 비어 있다", () => {
        const nav = categoryNav(categories, null)
        expect(nav.parents.map((c) => c.id)).toEqual([1, 2, 3])
        expect(nav.activeParent).toBeNull()
        expect(nav.children).toEqual([])
    })

    it("부모를 고르면 그 부모가 활성이고 자식 줄이 열린다", () => {
        const nav = categoryNav(categories, 1)
        expect(nav.activeParent?.id).toBe(1)
        expect(nav.children.map((c) => c.id)).toEqual([11, 12])
    })

    it("자식을 고르면 그 부모가 활성이고 형제들이 자식 줄에 남는다", () => {
        const nav = categoryNav(categories, 12)
        expect(nav.activeParent?.id).toBe(1)
        expect(nav.children.map((c) => c.id)).toEqual([11, 12])
    })

    it("자식 없는 부모나 모르는 id 는 자식 줄이 비어 있다", () => {
        expect(categoryNav(categories, 2).children).toEqual([])
        expect(categoryNav(categories, 2).activeParent?.id).toBe(2)
        expect(categoryNav(categories, 999).activeParent).toBeNull()
    })
})

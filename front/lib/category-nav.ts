// front/lib/category-nav.ts — 홈 상단 카테고리 내비(부모 chips 한 줄 + 고른 부모의 자식 chips 한 줄).
import type { Category } from "./types"

export interface CategoryNav {
    parents: Category[]
    /** 고른 카테고리 자신이 부모면 그것, 자식이면 그 부모. 선택 없음·모르는 id 면 null. */
    activeParent: Category | null
    /** 활성 부모의 자식들. 없으면 빈 배열. */
    children: Category[]
}

export function categoryNav(categories: Category[], selectedId: number | null): CategoryNav {
    const parents = categories
    if (selectedId === null) return { parents, activeParent: null, children: [] }
    for (const parent of parents) {
        const children = parent.children ?? []
        if (parent.id === selectedId || children.some((c) => c.id === selectedId)) {
            return { parents, activeParent: parent, children }
        }
    }
    return { parents, activeParent: null, children: [] }
}

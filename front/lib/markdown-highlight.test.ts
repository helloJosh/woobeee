import { describe, expect, it } from "vitest"

import {
    EXPECTED_FENCE_LANGUAGES,
    isHighlightable,
    missingLanguages,
} from "./markdown-highlight"

describe("markdown-highlight", () => {
    /**
     * 이 테스트가 이 모듈의 존재 이유다. rehype-highlight 는 모르는 언어를 만나도 던지지
     * 않고 색 없는 평문을 내므로, 문법 집합이 좁아지면 화면에서만 알 수 있다. 여기서
     * 이름으로 잡는다.
     */
    it("글에 쓰는 펜스 언어가 전부 하이라이팅된다", () => {
        expect(missingLanguages()).toEqual([])
    })

    it("요청받은 sql 과 java 가 들어 있다", () => {
        expect(isHighlightable("sql")).toBe(true)
        expect(isHighlightable("java")).toBe(true)
    })

    /** 별칭은 common 의 키 목록에 없다 -- 키만 대조하면 멀쩡한 별칭을 빠졌다고 잡는다. */
    it("별칭도 하이라이팅으로 친다", () => {
        expect(isHighlightable("ts")).toBe(true)
        expect(isHighlightable("js")).toBe(true)
        expect(isHighlightable("yml")).toBe(true)
        expect(isHighlightable("sh")).toBe(true)
    })

    it("대소문자와 앞뒤 공백은 무시한다", () => {
        expect(isHighlightable("SQL")).toBe(true)
        expect(isHighlightable("  Java  ")).toBe(true)
    })

    it("모르는 언어는 거짓이다", () => {
        expect(isHighlightable("brainfuck")).toBe(false)
        expect(isHighlightable("")).toBe(false)
    })

    /** 목록이 빈 채로 통과하면 위 테스트가 아무것도 지키지 않는다. */
    it("기대 목록이 비어 있지 않다", () => {
        expect(EXPECTED_FENCE_LANGUAGES.length).toBeGreaterThan(0)
    })

    it("빠진 언어를 이름으로 알려 준다", () => {
        expect(missingLanguages(["sql", "brainfuck"])).toEqual(["brainfuck"])
    })
})

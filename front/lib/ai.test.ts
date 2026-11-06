import { describe, expect, it } from "vitest"
import {
    allDecided, canSubmit, describeAction, imageErrorMessage, isTerminal, shouldPoll, statusLabel, validateImages,
    MAX_IMAGE_BYTES, MAX_IMAGES,
    type AiAction, type AiStatus,
} from "./ai"

const png = (size = 1) => ({ type: "image/png", size })
const STATUSES: AiStatus[] = ["RECEIVED", "PROCESSING", "DRAFTED", "APPLIED", "FAILED", "DEAD"]

describe("validateImages (AI-AC-18)", () => {
    it("AI-AC-18 accepts up to five png/jpeg/webp images of at most 5MB each — and none at all", () => {
        expect(validateImages([])).toBeNull()
        expect(validateImages(Array.from({ length: MAX_IMAGES }, () => png()))).toBeNull()
        expect(validateImages([{ type: "image/jpeg", size: 1 }, { type: "image/webp", size: MAX_IMAGE_BYTES }])).toBeNull()
    })

    it("AI-AC-18 mirrors the server limits: six images, 5MB+1 and non-image types are refused", () => {
        expect(validateImages(Array.from({ length: MAX_IMAGES + 1 }, () => png()))).toBe("tooMany")
        expect(validateImages([png(MAX_IMAGE_BYTES + 1)])).toBe("tooLarge")
        expect(validateImages([{ type: "image/gif", size: 1 }])).toBe("unsupported")
        expect(validateImages([{ type: "", size: 1 }])).toBe("unsupported")
        expect(validateImages([{ type: "application/pdf", size: 1 }])).toBe("unsupported")
    })

    it("AI-AC-18 reports the count problem before the per-file ones, like the server", () => {
        const many = Array.from({ length: MAX_IMAGES + 1 }, () => ({ type: "image/gif", size: MAX_IMAGE_BYTES + 1 }))

        expect(validateImages(many)).toBe("tooMany")
    })

    it("AI-AC-18 says in words what is wrong", () => {
        expect(imageErrorMessage("tooMany")).toContain("5")
        expect(imageErrorMessage("tooLarge")).toContain("5MB")
        expect(imageErrorMessage("unsupported")).toMatch(/png|jpeg|webp/)
    })
})

describe("request status", () => {
    it("AI-AC-18 polls only while the request is in flight", () => {
        expect(STATUSES.filter(shouldPoll)).toEqual(["RECEIVED", "PROCESSING", "FAILED"])
    })

    it("AI-AC-18 knows which statuses are final for the worker", () => {
        expect(STATUSES.filter(isTerminal)).toEqual(["DRAFTED", "APPLIED", "DEAD"])
    })

    it("AI-AC-18 blocks submitting while a request is in flight, and only then", () => {
        expect(canSubmit(null)).toBe(true)
        // 서버가 요청이 없을 때 data 를 null 이 아니라 빼 버려도(undefined) 화면이 죽으면 안 된다.
        expect(canSubmit(undefined)).toBe(true)
        expect(STATUSES.filter((status) => canSubmit({ status }))).toEqual(["DRAFTED", "APPLIED", "DEAD"])
    })

    it("AI-AC-18 has a label for every status", () => {
        for (const status of STATUSES) {
            expect(statusLabel(status).length, status).toBeGreaterThan(0)
        }
    })
})

describe("proposals", () => {
    const product = (status: AiAction["status"], payload: Record<string, unknown> = {}): AiAction => ({
        id: 1, type: "ROUND_PRODUCTS", status, payload: { roundId: 5, name: "GO패스 디럭스", price: 29, coins: 0, ...payload },
    })
    const task = (payload: Record<string, unknown>): AiAction => ({ id: 2, type: "TASK", status: "PENDING", payload })

    it("AI-AC-18 treats an empty proposal list as already decided ('제안 없음')", () => {
        expect(allDecided([])).toBe(true)
    })

    it("AI-AC-18 is decided only when nothing is pending", () => {
        expect(allDecided([product("APPLIED"), product("REJECTED")])).toBe(true)
        expect(allDecided([product("APPLIED"), product("PENDING")])).toBe(false)
    })

    it("AI-AC-18 describes a product proposal with its round, price and — when there are any — coins", () => {
        expect(describeAction(product("PENDING"))).toBe("차수 #5에 상품 추가 · GO패스 디럭스 · 가격 29")
        expect(describeAction(product("PENDING", { name: "500 코인", price: 149.5, coins: 500 })))
            .toBe("차수 #5에 상품 추가 · 500 코인 · 가격 149.5 · 포켓코인 500개")
    })

    it("AI-AC-18 describes a task proposal with its date, time and range", () => {
        expect(describeAction(task({ name: "치과 예약", startDate: "2026-11-20", startTime: "14:30" })))
            .toBe("할 일 추가 · 치과 예약 · 2026-11-20 14:30")
        expect(describeAction(task({ name: "출장", startDate: "2026-11-20", endDate: "2026-11-22" })))
            .toBe("할 일 추가 · 출장 · 2026-11-20 ~ 2026-11-22")
        expect(describeAction(task({ name: "언젠가" }))).toBe("할 일 추가 · 언젠가")
    })
})

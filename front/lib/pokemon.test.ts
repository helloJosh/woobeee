import { describe, expect, it } from "vitest"
import {
    MAX_COMMENT_LENGTH,
    STATUS_FLOW,
    STATUS_LABELS,
    canDeleteComment,
    canModifyOrder,
    effectiveRate,
    formatCoins,
    formatInr,
    formatKrw,
    formatRate,
    formatSignedKrw,
    fxDelta,
    isActive,
    isSettled,
    orderCoins,
    parseDonation,
    parseCoins,
    parseInr,
    pricePerCoin,
    quote,
    selectionLines,
    statusPercent,
    statusStep,
    summarize,
    toKrw,
    validateComment,
    validateProductDraft,
    validateOrderForm,
    type PokemonComment,
    type PokemonOrder,
    type PokemonOrderStatus,
    type PokemonProduct,
} from "./pokemon"

/** app-mvc 의 PokemonProduct 와 같은 표. 가격은 서버가 내려주므로 테스트에서도 주입한다. */
const PRODUCTS: PokemonProduct[] = [
    { id: 1, name: "100 PokéCoins", coins: 100, priceInr: 29 },
    { id: 2, name: "550 PokéCoins", coins: 550, priceInr: 149 },
    { id: 3, name: "1,200 PokéCoins", coins: 1200, priceInr: 289 },
    { id: 4, name: "2,500 PokéCoins", coins: 2500, priceInr: 589 },
    { id: 5, name: "5,200 PokéCoins", coins: 5200, priceInr: 1169 },
    { id: 6, name: "14,500 PokéCoins", coins: 14500, priceInr: 2899 },
    { id: 7, name: "Event Ticket", coins: 0, priceInr: 59 },
]

const RATE = 14.134663

function order(overrides: Partial<PokemonOrder> = {}): PokemonOrder {
    const base: PokemonOrder = {
        id: 1,
        applicantName: "친구",
        depositorName: null,
        guest: false,
        status: "ORDERED",
        totalInr: 2899,
        extraInr: 0,
        quotedRate: RATE,
        quotedAt: "2026-09-28T09:00:00",
        itemsKrw: 40976,
        donationKrw: 0,
        transferKrw: 40976,
        mine: false,
        settledRate: null,
        settledAt: null,
        memo: null,
        createdAt: "2026-09-28T09:00:00",
        items: [{ productId: 14500, productName: "14500 PokéCoins", coins: 14500, unitPriceInr: 2899, quantity: 1 }],
        totalCoins: 14500,
        comments: [],
    }
    return { ...base, ...overrides }
}

/** POKEMON-AC-08/17 */
describe("상태", () => {
    it("정상 경로는 신청에서 지급까지 네 단계다", () => {
        expect(STATUS_FLOW).toEqual(["ORDERED", "PREPARING", "DEPOSIT_CONFIRMED", "DELIVERED"])
    })

    it("신청만 한 상태도 1단계를 밟은 것으로 센다 — 0/4 로 비어 보이면 안 된다", () => {
        expect(statusStep("ORDERED")).toEqual({ step: 1, total: 4 })
        expect(statusStep("DELIVERED")).toEqual({ step: 4, total: 4 })
        expect(statusPercent("ORDERED")).toBe(25)
        expect(statusPercent("DELIVERED")).toBe(100)
    })

    it("취소는 진행 경로 밖이라 null 이다 — 0 이 아니다", () => {
        expect(statusStep("CANCELLED")).toBeNull()
        expect(statusPercent("CANCELLED")).toBeNull()
        expect(isActive(order({ status: "CANCELLED" }))).toBe(false)
    })

    it("모든 상태에 한국어 라벨이 있다", () => {
        const all: PokemonOrderStatus[] = [...STATUS_FLOW, "CANCELLED"]
        for (const status of all) {
            expect(STATUS_LABELS[status]).toBeTruthy()
        }
    })
})

/** POKEMON-AC-02 */
describe("상품 가성비", () => {
    it("큰 티어일수록 포켓코인당 싸진다", () => {
        const perCoin = PRODUCTS.filter((p) => p.coins > 0).map((p) => pricePerCoin(p) as number)

        for (let i = 1; i < perCoin.length; i++) {
            expect(perCoin[i]).toBeLessThan(perCoin[i - 1])
        }
    })

    it("코인 상품이 아니면 포켓코인당 단가가 없다 — 0으로 나누지 않는다", () => {
        const eventTicket = PRODUCTS.find((p) => p.coins === 0)!

        expect(pricePerCoin(eventTicket)).toBeNull()
    })
})

/** POKEMON-AC-04 */
describe("장바구니와 견적", () => {
    it("고른 것만 상품표 순서로 줄을 만든다", () => {
        const lines = selectionLines({ 6: 1, 1: 2, 2: 0 }, PRODUCTS)

        expect(lines.map((line) => line.product.name)).toEqual(["100 PokéCoins", "14,500 PokéCoins"])
        expect(lines[0].lineInr).toBe(58)
        expect(lines[0].lineCoins).toBe(200)
    })

    it("상품표에 없는 id 는 버린다 — 스토어에서 내려간 상품이다", () => {
        expect(selectionLines({ 999: 3 }, PRODUCTS)).toEqual([])
    })

    it("수량은 정수로 잘라서 센다", () => {
        expect(selectionLines({ 1: 2.9 }, PRODUCTS)[0].quantity).toBe(2)
    })

    it("이체 금액은 상품값 환산액에 기부금을 더한 값이다", () => {
        const result = quote({ 6: 1, 1: 1 }, PRODUCTS, RATE, 5000)

        expect(result.totalInr).toBe(2928)
        expect(result.totalCoins).toBe(14600)
        expect(result.itemsKrw).toBe(Math.round(2928 * RATE))
        expect(result.transferKrw).toBe(result.itemsKrw + 5000)
    })

    it("기부금은 음수일 수 없고 소수점은 잘린다", () => {
        expect(quote({ 1: 1 }, PRODUCTS, RATE, -9000).donationKrw).toBe(0)
        expect(quote({ 1: 1 }, PRODUCTS, RATE, 1500.9).donationKrw).toBe(1500)
    })

    it("아무것도 고르지 않으면 이체 금액은 기부금뿐이다", () => {
        const result = quote({}, PRODUCTS, RATE, 3000)

        expect(result.totalInr).toBe(0)
        expect(result.itemsKrw).toBe(0)
        expect(result.transferKrw).toBe(3000)
    })

    it("루피 환산은 반올림한다", () => {
        expect(toKrw(29, 14.134663)).toBe(410)
    })
})

/** POKEMON-AC-11 */
describe("환차손익", () => {
    it("환율이 내리면 받아 둔 돈이 남는다 — 이득", () => {
        const cheaper = RATE - 1

        expect(fxDelta(order(), cheaper)).toBeGreaterThan(0)
    })

    it("환율이 오르면 받아 둔 돈이 모자란다 — 손해", () => {
        const pricier = RATE + 1

        expect(fxDelta(order(), pricier)).toBeLessThan(0)
    })

    it("환율이 그대로면 손익이 없다", () => {
        expect(fxDelta(order(), RATE)).toBe(0)
    })

    /** POKEMON-AC-10 */
    it("준비중으로 넘어간 건은 현재 환율이 움직여도 손익이 확정돼 있다", () => {
        const settled = order({ status: "PREPARING", settledRate: RATE - 1, settledAt: "2026-09-28T12:00:00" })

        expect(isSettled(settled)).toBe(true)
        expect(effectiveRate(settled, 99)).toBe(RATE - 1)
        expect(fxDelta(settled, 99)).toBe(fxDelta(settled, 1))
    })

    it("아직 그 전이면 현재 환율로 평가한다", () => {
        const pending = order({ status: "PREPARING" })

        expect(isSettled(pending)).toBe(false)
        expect(effectiveRate(pending, 20)).toBe(20)
    })

    it("기부금은 환차에 섞이지 않는다", () => {
        const withDonation = order({ donationKrw: 10000, transferKrw: 50976 })

        expect(fxDelta(withDonation, RATE)).toBe(fxDelta(order(), RATE))
    })
})

/** POKEMON-AC-16/18 */
describe("집계", () => {
    const orders: PokemonOrder[] = [
        order({ id: 1, status: "ORDERED", applicantName: "가", totalInr: 29, itemsKrw: 410, transferKrw: 410 }),
        order({ id: 2, status: "PREPARING", applicantName: "나", totalInr: 149, itemsKrw: 2106, donationKrw: 1000, transferKrw: 3106 }),
        order({ id: 3, status: "DEPOSIT_CONFIRMED", applicantName: "다", totalInr: 289, itemsKrw: 4085, transferKrw: 4085, settledRate: RATE, settledAt: "x" }),
        order({ id: 4, status: "DELIVERED", applicantName: "라", totalInr: 589, itemsKrw: 8325, transferKrw: 8325, settledRate: RATE, settledAt: "x" }),
        order({ id: 5, status: "CANCELLED", applicantName: "마", totalInr: 2899, itemsKrw: 40976, transferKrw: 40976 }),
    ]

    it("상태별 건수는 취소까지 센다", () => {
        expect(summarize(orders).statusCounts).toEqual({
            ORDERED: 1, PREPARING: 1, DEPOSIT_CONFIRMED: 1, DELIVERED: 1, CANCELLED: 1,
        })
    })

    it("미입금 명단은 입금확인 전(주문·준비중) 신청서다 — 입금확인이 세 번째 단계다", () => {
        const summary = summarize(orders)

        expect(summary.awaitingDeposit.map((o) => o.applicantName)).toEqual(["가", "나"])
        expect(summary.outstandingKrw).toBe(410 + 3106)
    })

    /** POKEMON-AC-18 */
    it("배달 완료 전 금액만 따로 합산한다 — 운영자가 아직 끝내지 못한 총액", () => {
        const summary = summarize(orders)

        // 1·2·3번(대기·입금확인·배달중)만. 4번은 배달 완료, 5번은 취소라 빠진다.
        expect(summary.inFlightCount).toBe(3)
        expect(summary.inFlightKrw).toBe(410 + 3106 + 4085)
    })

    /** POKEMON-AC-18 */
    it("루피 합계와 기부금 뺀 금액을 따로 낸다 — 기부금은 상품값이 아니다", () => {
        const summary = summarize(orders)

        // 1·2·3번만. 2번은 기부 1,000원이 있지만 itemsKrw 에는 안 들어간다.
        expect(summary.inFlightInr).toBe(29 + 149 + 289)
        expect(summary.inFlightItemsKrw).toBe(410 + 2106 + 4085)
        // 이체 합계는 기부금을 포함하므로 그만큼 더 크다
        expect(summary.inFlightKrw - summary.inFlightItemsKrw).toBe(1000)
    })

    it("전부 배달 완료면 진행 중 금액이 0이다", () => {
        const summary = summarize([order({ status: "DELIVERED", transferKrw: 9999 })])

        expect(summary.inFlightCount).toBe(0)
        expect(summary.inFlightKrw).toBe(0)
        expect(summary.inFlightInr).toBe(0)
        expect(summary.inFlightItemsKrw).toBe(0)
    })

    it("취소는 진행 중에도 포켓코인 합계에도 들어가지 않는다", () => {
        const summary = summarize([
            order({ status: "CANCELLED", transferKrw: 9999, items: [
                { productId: 14500, productName: "14500 PokéCoins", coins: 14500, unitPriceInr: 2899, quantity: 1 },
            ] }),
        ])

        expect(summary.inFlightCount).toBe(0)
        expect(summary.inFlightKrw).toBe(0)
        expect(summary.totalCoins).toBe(0)
    })

    it("신청이 없어도 집계가 터지지 않는다", () => {
        const summary = summarize([])

        expect(summary.totalCoins).toBe(0)
        expect(summary.inFlightKrw).toBe(0)
        expect(summary.awaitingDeposit).toEqual([])
    })

    it("포켓코인 수는 항목에서 센다", () => {
        expect(orderCoins(order({
            items: [
                { productId: 100, productName: "100 PokéCoins", coins: 100, unitPriceInr: 29, quantity: 3 },
                { productId: 500, productName: "500 PokéCoins", coins: 500, unitPriceInr: 149, quantity: 2 },
            ],
        }))).toBe(1300)
    })
})

/** POKEMON-AC-03/07 */
describe("신청 폼 검증", () => {
    const filled = { applicantName: "친구", depositorName: "", donation: "", extraInr: "", selection: { 1: 1 } }

    it("통과하면 null 이다", () => {
        expect(validateOrderForm(filled, false)).toBeNull()
    })

    it("비회원은 이름이 필요하다", () => {
        expect(validateOrderForm({ ...filled, applicantName: "  " }, false)).toBe("이름을 입력해 주세요.")
    })

    it("로그인했으면 이름을 비워도 된다 — 서버가 닉네임으로 채운다", () => {
        expect(validateOrderForm({ ...filled, applicantName: "" }, true)).toBeNull()
    })

    it("상품을 하나도 고르지 않으면 거절한다", () => {
        expect(validateOrderForm({ ...filled, selection: {} }, true))
            .toBe("상품을 고르거나 루피를 직접 입력해 주세요.")
        expect(validateOrderForm({ ...filled, selection: { 1: 0 } }, true))
            .toBe("상품을 고르거나 루피를 직접 입력해 주세요.")
    })

    it("상품 하나당 99개까지다", () => {
        expect(validateOrderForm({ ...filled, selection: { 1: 100 } }, true))
            .toBe("상품 하나당 99개까지 신청할 수 있습니다.")
    })

    it("기부금이 숫자가 아니면 거절한다", () => {
        expect(validateOrderForm({ ...filled, donation: "만원" }, true))
            .toBe("기부금은 0 이상의 정수로 입력해 주세요.")
    })

    it("이름은 60자까지다", () => {
        expect(validateOrderForm({ ...filled, applicantName: "가".repeat(61) }, false))
            .toBe("이름은 60자까지 입력할 수 있습니다.")
    })
})

/** POKEMON-AC-04 */
describe("기부금 파싱", () => {
    it("빈 값은 기부 없음이다", () => {
        expect(parseDonation("")).toBe(0)
        expect(parseDonation("   ")).toBe(0)
    })

    it("콤마는 허용한다", () => {
        expect(parseDonation("10,000")).toBe(10000)
    })

    it("음수·소수·문자는 거절한다", () => {
        expect(parseDonation("-1")).toBeNull()
        expect(parseDonation("1.5")).toBeNull()
        expect(parseDonation("천원")).toBeNull()
    })
})

/** POKEMON-AC-11 */
describe("표시", () => {
    it("원화는 콤마를 찍고 반올림한다", () => {
        expect(formatKrw(40976.4)).toBe("40,976원")
    })

    it("손익은 부호를 항상 붙이고 0은 부호가 없다", () => {
        expect(formatSignedKrw(1240)).toBe("+1,240원")
        expect(formatSignedKrw(-1240)).toBe("−1,240원")
        expect(formatSignedKrw(0)).toBe("0원")
    })

    it("루피와 환율과 포켓코인 표기", () => {
        expect(formatInr(2899)).toContain("₹")
        expect(formatRate(14.134663)).toBe("₹1 = 14.1347원")
        expect(formatCoins(14500)).toBe("14,500 포켓코인")
    })
})

/** POKEMON-AC-13 */
describe("댓글", () => {
    function comment(overrides: Partial<PokemonComment> = {}): PokemonComment {
        return {
            id: 1,
            orderId: 1,
            authorName: "친구",
            guest: false,
            mine: false,
            content: "저도 낄게요",
            createdAt: "2026-09-28T10:00:00",
            ...overrides,
        }
    }

    it("비회원은 이름이 필요하다", () => {
        expect(validateComment({ authorName: " ", content: "안녕" }, false))
            .toBe("이름을 입력해 주세요.")
    })

    it("로그인했으면 이름을 비워도 된다", () => {
        expect(validateComment({ authorName: "", content: "안녕" }, true)).toBeNull()
    })

    it("빈 댓글은 거절한다", () => {
        expect(validateComment({ authorName: "친구", content: "   " }, false))
            .toBe("댓글 내용을 입력해 주세요.")
    })

    it("500자를 넘으면 거절한다", () => {
        const long = "가".repeat(MAX_COMMENT_LENGTH + 1)

        expect(validateComment({ authorName: "친구", content: long }, true))
            .toBe(`댓글은 ${MAX_COMMENT_LENGTH}자까지 쓸 수 있습니다.`)
    })

    it("딱 500자는 통과한다", () => {
        expect(validateComment({ authorName: "친구", content: "가".repeat(MAX_COMMENT_LENGTH) }, true))
            .toBeNull()
    })

    /** POKEMON-AC-09/13 */
    it("운영자는 모든 댓글을 지울 수 있다", () => {
        expect(canDeleteComment(comment({ mine: false }), true)).toBe(true)
    })

    it("운영자가 아니면 본인 댓글만 지울 수 있다", () => {
        expect(canDeleteComment(comment({ mine: true }), false)).toBe(true)
        expect(canDeleteComment(comment({ mine: false }), false)).toBe(false)
    })

    it("비회원 댓글은 아무도 본인이 아니다 — 운영자만 지운다", () => {
        const guestComment = comment({ guest: true, mine: false })

        expect(canDeleteComment(guestComment, false)).toBe(false)
        expect(canDeleteComment(guestComment, true)).toBe(true)
    })
})

/** POKEMON-AC-15 */
describe("총 포켓코인", () => {
    it("여러 신청서의 포켓코인을 합산한다", () => {
        const summary = summarize([
            order({ id: 1, items: [{ productId: 100, productName: "100 PokéCoins", coins: 100, unitPriceInr: 29, quantity: 2 }] }),
            order({ id: 2, items: [{ productId: 500, productName: "500 PokéCoins", coins: 500, unitPriceInr: 149, quantity: 1 }] }),
            order({ id: 3, status: "CANCELLED", items: [{ productId: 14500, productName: "14500 PokéCoins", coins: 14500, unitPriceInr: 2899, quantity: 1 }] }),
        ])

        // 취소분 14,500 은 빠진다
        expect(summary.totalCoins).toBe(700)
    })
})

/** POKEMON-AC-12 */
describe("신청서 수정·삭제 권한", () => {
    it("운영자는 상태와 주인을 가리지 않고 고치고 지울 수 있다", () => {
        expect(canModifyOrder(order({ guest: false, mine: false, status: "DEPOSIT_CONFIRMED" }), true)).toBe(true)
    })

    it("회원이 낸 신청서는 그 회원만 건드린다", () => {
        expect(canModifyOrder(order({ guest: false, mine: true }), false)).toBe(true)
        expect(canModifyOrder(order({ guest: false, mine: false }), false)).toBe(false)
    })

    it("비회원이 낸 신청서는 주인이 없으므로 누구나 건드린다", () => {
        expect(canModifyOrder(order({ guest: true, mine: false }), false)).toBe(true)
    })

    it("준비가 시작된 뒤로는 본인도 못 고치고 못 지운다 — 운영자만", () => {
        for (const status of ["PREPARING", "DEPOSIT_CONFIRMED", "DELIVERED", "CANCELLED"] as const) {
            expect(canModifyOrder(order({ guest: false, mine: true, status }), false)).toBe(false)
            expect(canModifyOrder(order({ guest: true, mine: false, status }), false)).toBe(false)
        }
    })
})

/** POKEMON-AC-19 */
describe("자유 입력 루피", () => {
    const filled = { applicantName: "친구", depositorName: "", donation: "", extraInr: "", selection: {} }

    it("상품을 고르지 않아도 루피를 적었으면 통과한다", () => {
        expect(validateOrderForm({ ...filled, extraInr: "250" }, true)).toBeNull()
    })

    it("상품도 루피도 없으면 거절한다", () => {
        expect(validateOrderForm(filled, true)).toBe("상품을 고르거나 루피를 직접 입력해 주세요.")
    })

    it("소수 둘째 자리까지 허용한다 — 스토어 가격이 ₹29.00 꼴이다", () => {
        expect(parseInr("29.00")).toBe(29)
        expect(parseInr("1,169.50")).toBe(1169.5)
        expect(parseInr("")).toBe(0)
    })

    it("음수·셋째 자리·문자는 거절한다", () => {
        expect(parseInr("-1")).toBeNull()
        expect(parseInr("29.001")).toBeNull()
        expect(parseInr("루피")).toBeNull()
        expect(validateOrderForm({ ...filled, extraInr: "-5" }, true))
            .toBe("루피는 0 이상의 숫자로 입력해 주세요 (소수점 둘째 자리까지).")
    })

    it("견적의 루피 합계에 더해지고 환산에도 반영된다", () => {
        const withExtra = quote({ 1: 1 }, PRODUCTS, RATE, 0, 250)

        expect(withExtra.totalInr).toBe(29 + 250)
        expect(withExtra.itemsKrw).toBe(Math.round(279 * RATE))
        // 포켓코인 수는 상품에서만 나온다 — 자유 입력 루피는 무엇을 살지 모른다
        expect(withExtra.totalCoins).toBe(100)
    })

    it("루피만으로도 견적이 선다", () => {
        const onlyExtra = quote({}, PRODUCTS, RATE, 0, 500)

        expect(onlyExtra.totalInr).toBe(500)
        expect(onlyExtra.totalCoins).toBe(0)
        expect(onlyExtra.transferKrw).toBe(Math.round(500 * RATE))
    })

    it("음수 루피는 0으로 본다", () => {
        expect(quote({}, PRODUCTS, RATE, 0, -100).totalInr).toBe(0)
    })
})

/** POKEMON-AC-24 */
describe("상품 관리 검증", () => {
    const draft = { name: "GO패스 디럭스: 11월", priceInr: "229.00", coins: "", active: true }

    it("패스는 포켓코인 없이 등록된다 — 빈 값은 0이다", () => {
        expect(validateProductDraft(draft)).toBeNull()
        expect(parseCoins("")).toBe(0)
    })

    it("코인 팩은 코인 수를 받는다", () => {
        expect(validateProductDraft({ ...draft, name: "550 PokéCoins", coins: "550" })).toBeNull()
        expect(parseCoins("14,500")).toBe(14500)
    })

    it("이름 없이 등록할 수 없다", () => {
        expect(validateProductDraft({ ...draft, name: "  " })).toBe("상품 이름을 입력해 주세요.")
    })

    it("가격은 0보다 커야 한다 — 공짜 상품은 신청 대상이 아니다", () => {
        expect(validateProductDraft({ ...draft, priceInr: "0" }))
            .toBe("루피 가격은 0보다 큰 숫자로 입력해 주세요 (소수점 둘째 자리까지).")
        expect(validateProductDraft({ ...draft, priceInr: "" }))
            .toBe("루피 가격은 0보다 큰 숫자로 입력해 주세요 (소수점 둘째 자리까지).")
    })

    it("코인 수는 음수·소수를 받지 않는다", () => {
        expect(parseCoins("-1")).toBeNull()
        expect(parseCoins("1.5")).toBeNull()
        expect(validateProductDraft({ ...draft, coins: "-5" }))
            .toBe("포켓코인 수는 0 이상의 정수로 입력해 주세요 (패스·티켓은 0).")
    })

    it("이름은 200자까지다", () => {
        expect(validateProductDraft({ ...draft, name: "가".repeat(201) }))
            .toBe("상품 이름은 200자까지 입력할 수 있습니다.")
    })
})

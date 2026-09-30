import { describe, expect, it } from "vitest"
import {
    ORDER_STATUS_FLOW,
    ORDER_STATUS_LABELS,
    ROUND_STATUS_FLOW,
    ROUND_STATUS_LABELS,
    acceptsOrders,
    canDeleteComment,
    canModifyOrder,
    effectiveRate,
    formatAmount,
    formatCoins,
    formatKrw,
    formatRate,
    formatSignedKrw,
    fxDelta,
    isRoundSettled,
    orderCoins,
    orderStep,
    paginate,
    ORDERS_PER_PAGE,
    parseAmount,
    parseCoins,
    parseDonation,
    percentOf,
    pricePerCoin,
    quote,
    rateFor,
    roundStep,
    roundTitle,
    selectionLines,
    summarizeRound,
    validateComment,
    validateHandle,
    validateOrderForm,
    validateProductDraft,
    validateRoundDraft,
    type PokemonComment,
    type PokemonOrder,
    type PokemonProduct,
    type PokemonRound,
} from "./pokemon"

const CURRENCIES = ["INR", "USD", "JPY"]
const RATE = 14.134663

/** 인도 차수의 상품표. 마지막 하나는 코인이 아닌 패스다. */
const PRODUCTS: PokemonProduct[] = [
    { id: 1, name: "100 PokéCoins", currency: "INR", coins: 100, price: 29 },
    { id: 2, name: "550 PokéCoins", currency: "INR", coins: 550, price: 149 },
    { id: 3, name: "14,500 PokéCoins", currency: "INR", coins: 14500, price: 2899 },
    { id: 4, name: "GO패스 디럭스: 10월", currency: "INR", coins: 0, price: 229 },
]

function round(overrides: Partial<PokemonRound> = {}): PokemonRound {
    return {
        id: 1,
        hostHandle: "byungwoo",
        hostName: "병우",
        sequence: 1,
        title: null,
        currency: "INR",
        rateMode: "FIXED",
        quotedRate: RATE,
        quotedAt: "2026-09-29T09:00:00",
        bankAccount: "하나은행 111 병우",
        deadline: null,
        status: "OPEN",
        settledRate: null,
        settledAt: null,
        memo: null,
        createdAt: "2026-09-29T09:00:00",
        canManage: false,
        orderCount: 0,
        transferKrwTotal: 0,
        ...overrides,
    }
}

function order(overrides: Partial<PokemonOrder> = {}): PokemonOrder {
    return {
        id: 1,
        roundId: 1,
        applicantName: "친구",
        depositorName: null,
        guest: false,
        mine: false,
        status: "ORDERED",
        totalAmount: 2899,
        extraAmount: 0,
        quotedRate: RATE,
        quotedAt: "2026-09-29T09:00:00",
        itemsKrw: 40976,
        donationKrw: 0,
        transferKrw: 40976,
        memo: null,
        createdAt: "2026-09-29T09:00:00",
        items: [{ productId: 3, productName: "14,500 PokéCoins", coins: 14500, unitPrice: 2899, quantity: 1 }],
        totalCoins: 14500,
        comments: [],
        ...overrides,
    }
}

/** POKEMON-AC-08 */
describe("두 층의 상태", () => {
    it("차수는 네 단계, 신청서는 세 단계다", () => {
        expect(ROUND_STATUS_FLOW).toEqual(["OPEN", "CLOSED", "PURCHASED", "DELIVERED"])
        expect(ORDER_STATUS_FLOW).toEqual(["ORDERED", "DEPOSIT_CONFIRMED", "DELIVERED"])
    })

    it("첫 단계도 1로 센다 — 갓 시작한 것이 0% 로 비어 보이면 안 된다", () => {
        expect(roundStep("OPEN")).toEqual({ step: 1, total: 4 })
        expect(orderStep("ORDERED")).toEqual({ step: 1, total: 3 })
        expect(percentOf(orderStep("ORDERED"))).toBeCloseTo(33.33, 1)
        expect(percentOf(orderStep("DELIVERED"))).toBe(100)
    })

    it("취소는 진행 경로 밖이라 null 이다 — 0 이 아니다", () => {
        expect(roundStep("CANCELLED")).toBeNull()
        expect(orderStep("CANCELLED")).toBeNull()
        expect(percentOf(null)).toBeNull()
    })

    it("모든 상태에 한국어 라벨이 있다", () => {
        for (const s of [...ROUND_STATUS_FLOW, "CANCELLED" as const]) {
            expect(ROUND_STATUS_LABELS[s]).toBeTruthy()
        }
        for (const s of [...ORDER_STATUS_FLOW, "CANCELLED" as const]) {
            expect(ORDER_STATUS_LABELS[s]).toBeTruthy()
        }
    })
})

/** POKEMON-AC-27 */
describe("차수 환율", () => {
    it("FIXED 면 지금 환율이 아니라 차수 환율을 쓴다", () => {
        expect(rateFor(round({ rateMode: "FIXED", quotedRate: 14.13 }), 99)).toBe(14.13)
    })

    it("PER_ORDER 면 지금 환율을 쓴다", () => {
        expect(rateFor(round({ rateMode: "PER_ORDER", quotedRate: 14.13 }), 99)).toBe(99)
    })

    it("미리보기 금액이 그 환율로 계산된다 — 저장될 금액과 어긋나면 안 된다", () => {
        const fixed = round({ rateMode: "FIXED", quotedRate: 14.13 })
        const preview = quote({ 2: 2 }, PRODUCTS, rateFor(fixed, 99), 0)

        expect(preview.totalAmount).toBe(298)
        expect(preview.itemsKrw).toBe(Math.round(298 * 14.13))
    })
})

/** POKEMON-AC-28 */
describe("차수 범위", () => {
    it("모집중일 때만 신청을 받는다", () => {
        expect(acceptsOrders(round({ status: "OPEN" }))).toBe(true)
        for (const status of ["CLOSED", "PURCHASED", "DELIVERED", "CANCELLED"] as const) {
            expect(acceptsOrders(round({ status }))).toBe(false)
        }
    })
})

/** POKEMON-AC-04 */
describe("장바구니와 견적", () => {
    it("고른 것만 상품표 순서로 줄을 만든다", () => {
        const lines = selectionLines({ 3: 1, 1: 2, 2: 0 }, PRODUCTS)

        expect(lines.map((l) => l.product.name)).toEqual(["100 PokéCoins", "14,500 PokéCoins"])
        expect(lines[0].lineAmount).toBe(58)
        expect(lines[0].lineCoins).toBe(200)
    })

    it("상품표에 없는 id 는 버린다 — 내려간 상품이다", () => {
        expect(selectionLines({ 999: 3 }, PRODUCTS)).toEqual([])
    })

    it("이체 금액은 상품값 환산액에 기부금을 더한 값이다", () => {
        const result = quote({ 3: 1, 1: 1 }, PRODUCTS, RATE, 5000)

        expect(result.totalAmount).toBe(2928)
        expect(result.totalCoins).toBe(14600)
        expect(result.transferKrw).toBe(result.itemsKrw + 5000)
    })

    it("기부금은 음수일 수 없고 소수점은 잘린다", () => {
        expect(quote({ 1: 1 }, PRODUCTS, RATE, -9000).donationKrw).toBe(0)
        expect(quote({ 1: 1 }, PRODUCTS, RATE, 1500.9).donationKrw).toBe(1500)
    })
})

/** POKEMON-AC-19 */
describe("자유 입력 금액", () => {
    it("상품 없이 금액만으로도 견적이 선다", () => {
        const onlyExtra = quote({}, PRODUCTS, RATE, 0, 500)

        expect(onlyExtra.totalAmount).toBe(500)
        expect(onlyExtra.totalCoins).toBe(0)
    })

    it("소수 둘째 자리까지 받는다 — 스토어 가격이 $0.99 꼴이다", () => {
        expect(parseAmount("0.99")).toBe(0.99)
        expect(parseAmount("1,169.50")).toBe(1169.5)
        expect(parseAmount("")).toBe(0)
        expect(parseAmount("-1")).toBeNull()
        expect(parseAmount("29.001")).toBeNull()
    })

    it("음수는 0으로 본다", () => {
        expect(quote({}, PRODUCTS, RATE, 0, -100).totalAmount).toBe(0)
    })
})

/** POKEMON-AC-11 */
describe("환차손익", () => {
    it("환율이 오르면 받아 둔 돈이 모자란다 — 손해", () => {
        expect(fxDelta(order(), round(), RATE + 1)).toBeLessThan(0)
    })

    it("환율이 내리면 남는다 — 이득", () => {
        expect(fxDelta(order(), round(), RATE - 1)).toBeGreaterThan(0)
    })

    /** POKEMON-AC-10 */
    it("차수가 결제까지 끝났으면 현재 환율이 움직여도 손익이 확정돼 있다", () => {
        const settled = round({ status: "PURCHASED", settledRate: RATE - 1, settledAt: "x" })

        expect(isRoundSettled(settled)).toBe(true)
        expect(effectiveRate(settled, 99)).toBe(RATE - 1)
        expect(fxDelta(order(), settled, 99)).toBe(fxDelta(order(), settled, 1))
    })

    it("기부금은 환차에 섞이지 않는다", () => {
        const donated = order({ donationKrw: 10000, transferKrw: 50976 })

        expect(fxDelta(donated, round(), RATE)).toBe(fxDelta(order(), round(), RATE))
    })
})

/** POKEMON-AC-16 */
describe("차수 집계", () => {
    const orders: PokemonOrder[] = [
        order({ id: 1, status: "ORDERED", applicantName: "가", totalAmount: 29, itemsKrw: 410, transferKrw: 410 }),
        order({ id: 2, status: "DEPOSIT_CONFIRMED", applicantName: "나", totalAmount: 149, itemsKrw: 2106, donationKrw: 1000, transferKrw: 3106 }),
        order({ id: 3, status: "DELIVERED", applicantName: "다", totalAmount: 289, itemsKrw: 4085, transferKrw: 4085 }),
        order({ id: 4, status: "CANCELLED", applicantName: "라", totalAmount: 2899, itemsKrw: 40976, transferKrw: 40976 }),
    ]

    it("취소는 금액 집계에서 빠진다", () => {
        const s = summarizeRound(orders, round(), RATE)

        expect(s.totalAmount).toBe(29 + 149 + 289)
        expect(s.totalTransferKrw).toBe(410 + 3106 + 4085)
        expect(s.statusCounts.CANCELLED).toBe(1)
    })

    it("미입금은 주문 단계의 신청서다", () => {
        const s = summarizeRound(orders, round(), RATE)

        expect(s.awaitingDeposit.map((o) => o.applicantName)).toEqual(["가"])
        expect(s.outstandingKrw).toBe(410)
    })

    it("기부금을 뺀 상품값도 따로 낸다 — 주최자가 실제로 써야 할 돈과 섞이면 안 된다", () => {
        const s = summarizeRound(orders, round(), RATE)

        expect(s.totalItemsKrw).toBe(410 + 2106 + 4085)
        expect(s.totalTransferKrw - s.totalItemsKrw).toBe(1000)
    })

    it("신청이 없어도 터지지 않는다", () => {
        const s = summarizeRound([], round(), RATE)

        expect(s.totalAmount).toBe(0)
        expect(s.fxKrw).toBe(0)
        expect(s.awaitingDeposit).toEqual([])
    })

    it("포켓코인 수는 항목에서 센다", () => {
        expect(orderCoins(order({
            items: [
                { productId: 1, productName: "100 PokéCoins", coins: 100, unitPrice: 29, quantity: 3 },
                { productId: 2, productName: "550 PokéCoins", coins: 550, unitPrice: 149, quantity: 2 },
            ],
        }))).toBe(1400)
    })
})

/** POKEMON-AC-12 */
describe("신청서 수정·삭제 권한", () => {
    it("주최자는 상태와 주인을 가리지 않는다", () => {
        expect(canModifyOrder(order({ guest: false, mine: false, status: "DELIVERED" }), true)).toBe(true)
    })

    it("회원이 낸 것은 그 회원만", () => {
        expect(canModifyOrder(order({ guest: false, mine: true }), false)).toBe(true)
        expect(canModifyOrder(order({ guest: false, mine: false }), false)).toBe(false)
    })

    it("주인이 없는 옛 비회원 신청서는 주최자만 — 본인 확인이 성립하지 않는다", () => {
        expect(canModifyOrder(order({ guest: true, mine: false }), false)).toBe(false)
        expect(canModifyOrder(order({ guest: true, mine: false }), true)).toBe(true)
    })

    it("입금이 확인된 뒤로는 주최자만", () => {
        for (const status of ["DEPOSIT_CONFIRMED", "DELIVERED", "CANCELLED"] as const) {
            expect(canModifyOrder(order({ mine: true, status }), false)).toBe(false)
        }
    })
})

/** POKEMON-AC-13 */
describe("댓글", () => {
    const comment = (o: Partial<PokemonComment> = {}): PokemonComment => ({
        id: 1, orderId: 1, authorName: "친구", guest: false, mine: false,
        content: "저도 낄게요", createdAt: "2026-09-29T10:00:00", ...o,
    })

    it("주최자는 모든 댓글을, 그 밖에는 본인 것만 지운다", () => {
        expect(canDeleteComment(comment({ mine: false }), true)).toBe(true)
        expect(canDeleteComment(comment({ mine: true }), false)).toBe(true)
        expect(canDeleteComment(comment({ mine: false }), false)).toBe(false)
    })

    it("빈 댓글은 거절한다 — 이름은 회원 닉네임이라 받지 않는다", () => {
        expect(validateComment({ content: "안녕" })).toBeNull()
        expect(validateComment({ content: "  " })).toBe("댓글 내용을 입력해 주세요.")
    })
})

/** POKEMON-AC-03/07 */
describe("신청 폼 검증", () => {
    const filled = { depositorName: "", donation: "", extraAmount: "", selection: { 1: 1 } }

    it("통과하면 null 이다", () => {
        expect(validateOrderForm(filled)).toBeNull()
    })

    it("상품도 금액도 없으면 거절한다", () => {
        expect(validateOrderForm({ ...filled, selection: {} }))
            .toBe("상품을 고르거나 금액을 직접 입력해 주세요.")
    })

    it("상품 없이 금액만 적었으면 통과한다", () => {
        expect(validateOrderForm({ ...filled, selection: {}, extraAmount: "250" })).toBeNull()
    })

    it("상품 하나당 99개까지다", () => {
        expect(validateOrderForm({ ...filled, selection: { 1: 100 } }))
            .toBe("상품 하나당 99개까지 신청할 수 있습니다.")
    })

    it("기부금이 숫자가 아니면 거절한다", () => {
        expect(validateOrderForm({ ...filled, donation: "만원" }))
            .toBe("기부금은 0 이상의 정수로 입력해 주세요.")
        expect(parseDonation("10,000")).toBe(10000)
        expect(parseDonation("-1")).toBeNull()
    })
})

/** POKEMON-AC-24 */
describe("상품 검증", () => {
    const draft = { name: "GO패스 디럭스: 11월", currency: "INR", price: "229.00", coins: "", active: true }

    it("패스는 포켓코인 없이 등록된다 — 빈 값은 0이다", () => {
        expect(validateProductDraft(draft, CURRENCIES)).toBeNull()
        expect(parseCoins("")).toBe(0)
        expect(parseCoins("14,500")).toBe(14500)
    })

    it("지원하지 않는 통화는 거절한다", () => {
        expect(validateProductDraft({ ...draft, currency: "EUR" }, CURRENCIES))
            .toBe("지원하지 않는 통화입니다.")
    })

    it("이름과 가격은 비울 수 없다", () => {
        expect(validateProductDraft({ ...draft, name: " " }, CURRENCIES))
            .toBe("상품 이름을 입력해 주세요.")
        expect(validateProductDraft({ ...draft, price: "0" }, CURRENCIES))
            .toBe("가격은 0보다 큰 숫자로 입력해 주세요 (소수점 둘째 자리까지).")
    })

    it("코인이 아닌 상품은 코인당 단가가 없다 — 0으로 나누지 않는다", () => {
        expect(pricePerCoin(PRODUCTS[3])).toBeNull()
        expect(pricePerCoin(PRODUCTS[0])).toBeCloseTo(0.29)
    })
})

/** POKEMON-AC-25 */
describe("차수 개설 검증", () => {
    const draft = {
        title: "", currency: "INR", rateMode: "FIXED" as const, quotedRate: "",
        bankAccount: "하나은행 111 병우", deadline: "", memo: "",
    }

    it("계좌 없이 열 수 없다 — 어디로 보낼지 모른다", () => {
        expect(validateRoundDraft(draft, CURRENCIES)).toBeNull()
        expect(validateRoundDraft({ ...draft, bankAccount: "  " }, CURRENCIES))
            .toContain("입금받을 계좌를 적어 주세요.")
    })

    /** POKEMON-AC-30 */
    it("상품 관리에 기본 계좌가 있으면 차수에서 비워도 된다 — 서버가 그것을 쓴다", () => {
        expect(validateRoundDraft({ ...draft, bankAccount: "" }, CURRENCIES, true)).toBeNull()
        expect(validateRoundDraft({ ...draft, bankAccount: "" }, CURRENCIES, false))
            .toContain("입금받을 계좌를 적어 주세요.")
    })

    it("환율을 비우면 지금 환율을 쓴다 — 통과해야 한다", () => {
        expect(validateRoundDraft({ ...draft, quotedRate: "" }, CURRENCIES)).toBeNull()
    })

    it("환율을 적었으면 0보다 커야 한다", () => {
        expect(validateRoundDraft({ ...draft, quotedRate: "0" }, CURRENCIES))
            .toBe("환율은 0보다 큰 숫자로 입력해 주세요.")
        expect(validateRoundDraft({ ...draft, quotedRate: "14.13" }, CURRENCIES)).toBeNull()
    })

    it("PER_ORDER 면 환율 칸을 보지 않는다", () => {
        expect(validateRoundDraft({ ...draft, rateMode: "PER_ORDER", quotedRate: "엉터리" }, CURRENCIES))
            .toBeNull()
    })
})

/** POKEMON-AC-26 */
describe("주최자 주소", () => {
    it("영소문자·숫자·하이픈으로 2~30자다", () => {
        expect(validateHandle("byungwoo")).toBeNull()
        expect(validateHandle("hong-seok2")).toBeNull()
        expect(validateHandle("A")).not.toBeNull()
        expect(validateHandle("병우")).not.toBeNull()
        expect(validateHandle("-start")).not.toBeNull()
    })

    it("정적 경로와 겹치는 말은 막는다 — /pokemon/products 가 가려 버린다", () => {
        for (const reserved of ["products", "orders", "new"]) {
            expect(validateHandle(reserved)).toBe("이미 쓰이고 있는 주소입니다. 다른 주소를 골라 주세요.")
        }
    })
})

describe("표시", () => {
    it("통화 기호가 붙고 소수는 있을 때만 보인다", () => {
        expect(formatAmount(2899, "INR")).toBe("₹2,899")
        expect(formatAmount(0.99, "USD")).toBe("$0.99")
        expect(formatAmount(500, "JPY")).toBe("¥500")
    })

    it("모르는 통화는 코드를 그대로 붙인다", () => {
        expect(formatAmount(10, "EUR")).toBe("EUR 10")
    })

    it("손익은 부호를 항상 붙이고 0은 부호가 없다", () => {
        expect(formatSignedKrw(1240)).toBe("+1,240원")
        expect(formatSignedKrw(-1240)).toBe("−1,240원")
        expect(formatSignedKrw(0)).toBe("0원")
    })

    it("원화·환율·코인 표기", () => {
        expect(formatKrw(40976.4)).toBe("40,976원")
        expect(formatRate(14.134663, "INR")).toBe("₹1 = 14.1347원")
        expect(formatCoins(14500)).toBe("14,500 포켓코인")
    })

    it("제목이 없으면 몇 차인지로 부른다", () => {
        expect(roundTitle(round({ title: null, sequence: 3 }))).toBe("3차 공동구매")
        expect(roundTitle(round({ title: "추석 공구" }))).toBe("추석 공구")
    })
})

/** POKEMON-AC-31 */
describe("페이징", () => {
    const items = Array.from({ length: 23 }, (_, i) => i + 1)

    it("한 페이지에 10개씩 자른다", () => {
        expect(ORDERS_PER_PAGE).toBe(10)
        expect(paginate(items, 1).items).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
        expect(paginate(items, 3).items).toEqual([21, 22, 23])
        expect(paginate(items, 1).totalPages).toBe(3)
        expect(paginate(items, 1).total).toBe(23)
    })

    it("빈 목록도 1페이지다 — 0페이지는 없다", () => {
        const page = paginate([], 1)

        expect(page.page).toBe(1)
        expect(page.totalPages).toBe(1)
        expect(page.items).toEqual([])
    })

    it("범위를 벗어난 페이지는 끝으로 당긴다 — 마지막 항목을 지워도 빈 화면이 뜨지 않게", () => {
        expect(paginate(items, 99).page).toBe(3)
        expect(paginate(items, 99).items).toEqual([21, 22, 23])
    })

    it("0이나 음수도 1페이지로 본다", () => {
        expect(paginate(items, 0).page).toBe(1)
        expect(paginate(items, -5).page).toBe(1)
    })

    it("딱 나눠떨어지면 빈 마지막 페이지를 만들지 않는다", () => {
        expect(paginate(Array.from({ length: 20 }, (_, i) => i), 1).totalPages).toBe(2)
    })
})


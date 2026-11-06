// front/lib/pokemon.ts — 공동구매의 React-free 판단 로직.
// 컴포넌트에는 판단을 두지 않는다 (vitest 가 node 환경이라 컴포넌트는 검증 밖이다).
//
// 구조: 주최자가 차수(1차·2차·3차)를 열고 친구들이 거기에 신청한다.
// 통화·계좌는 차수가 들고, 개인별로 다른 것(입금)은 신청서가 든다.
// 원화는 저장하지 않는다 — 하루 한 번 갱신되는 오늘 환율로 매번 계산한 "예상 금액"이다.

/* ===== 서버 계약 (app-mvc pokemon 도메인과 1:1) ===== */

/** 차수의 진행 단계. 주최자가 전체에 대해 하는 일이다. */
export type PokemonRoundStatus = "OPEN" | "CLOSED" | "PURCHASED" | "DELIVERED" | "CANCELLED"

/** 신청서의 진행 단계. 개인별로 다른 것만 여기 있다. */
export type PokemonOrderStatus = "ORDERED" | "DEPOSIT_CONFIRMED" | "DELIVERED" | "CANCELLED"

export interface PokemonRound {
    id: number
    /** URL 조각 — /pokemon/{hostHandle}/{sequence} */
    hostHandle: string
    hostName: string
    sequence: number
    title: string | null
    /** ISO 4217. 이 차수의 상품·금액이 모두 이 통화다. */
    currency: string
    bankAccount: string
    deadline: string | null
    status: PokemonRoundStatus
    memo: string | null
    createdAt: string
    /** 이 화면을 보는 사람이 이 차수를 주무를 수 있는가. 서버가 판단한다. */
    canManage: boolean
    orderCount: number
}

export interface PokemonProduct {
    id: number
    /** 스토어에 적힌 이름 그대로. */
    name: string
    currency: string
    /** 포켓코인 수. 패스·티켓처럼 코인이 아닌 상품은 0. */
    coins: number
    price: number
}

/** 운영자 관리 화면이 보는 상품. 내려간 것까지 나오고 지울 수 있는지도 알려준다. */
export interface PokemonManagedProduct extends PokemonProduct {
    sortOrder: number
    active: boolean
    /** 신청서에 쓰인 적이 있으면 지울 수 없다 — 내리기만 된다. */
    inUse: boolean
    updatedAt: string | null
}

export interface PokemonOrderItem {
    productId: number | null
    /** 신청 당시 이름·단가 스냅샷 — 상품표가 바뀌어도 과거 신청서는 이 값을 유지한다. */
    productName: string
    coins: number
    unitPrice: number
    quantity: number
}

export interface PokemonComment {
    id: number
    orderId: number
    authorName: string
    guest: boolean
    /** 이 화면을 보는 사람이 쓴 댓글이면 true. 서버가 판단한다. */
    mine: boolean
    content: string
    createdAt: string
    /** 고친 적이 없으면 null. 화면이 "수정됨" 을 붙일지 이 값으로 정한다. */
    updatedAt: string | null
}

export interface PokemonOrder {
    id: number
    roundId: number
    applicantName: string
    depositorName: string | null
    guest: boolean
    /** 이 화면을 보는 사람이 낸 신청서면 true. 서버가 판단한다. */
    mine: boolean
    status: PokemonOrderStatus
    /** 차수 통화 기준의 상품 합계 + 자유 입력 금액. */
    totalAmount: number
    /** 상품표에 없는 것을 위한 자유 입력 금액. 없으면 0. */
    extraAmount: number
    /** round(루피 합계 × 오늘 환율). 기부금을 뺀 예상 상품값. */
    estimatedItemsKrw: number
    donationKrw: number
    /** estimatedItemsKrw + donationKrw — 예상 이체 금액. 실제 청구와 다를 수 있다. */
    estimatedKrw: number
    memo: string | null
    createdAt: string
    items: PokemonOrderItem[]
    totalCoins: number
    comments: PokemonComment[]
}

export interface PokemonExchangeRate {
    currency: string
    /** 1 currency = N KRW. */
    toKrw: number
    fetchedAt: string
    /** 외부 조회가 실패해 마지막 성공값을 쓰는 중이면 true. */
    stale: boolean
}

export interface PokemonHome {
    /** 내 주소. 없으면 차수를 열기 전에 먼저 정해야 한다. */
    myHandle: string | null
    /** 내 기본 입금 계좌 — 차수를 열 때 자동으로 채운다. */
    myBankAccount: string | null
    loggedIn: boolean
    currencies: string[]
}

export interface PokemonHost {
    handle: string
    name: string
    isMe: boolean
    /** 기본 입금 계좌. 본인에게만 내려온다. */
    bankAccount: string | null
    rounds: PokemonRound[]
}

export interface PokemonRoundBoard {
    round: PokemonRound
    currentRate: PokemonExchangeRate
    products: PokemonProduct[]
    orders: PokemonOrder[]
}

export interface PokemonOrderDetail {
    rate: PokemonExchangeRate
    round: PokemonRound
    order: PokemonOrder
    canManage: boolean
}

/* ===== 상태 ===== */

export const ROUND_STATUS_FLOW: PokemonRoundStatus[] = ["OPEN", "CLOSED", "PURCHASED", "DELIVERED"]

export const ROUND_STATUS_LABELS: Record<PokemonRoundStatus, string> = {
    OPEN: "모집중",
    CLOSED: "마감",
    PURCHASED: "구매완료",
    DELIVERED: "배달완료",
    CANCELLED: "취소",
}

export const ORDER_STATUS_FLOW: PokemonOrderStatus[] = ["ORDERED", "DEPOSIT_CONFIRMED", "DELIVERED"]

export const ORDER_STATUS_LABELS: Record<PokemonOrderStatus, string> = {
    ORDERED: "주문",
    DEPOSIT_CONFIRMED: "입금확인",
    DELIVERED: "전달완료",
    CANCELLED: "취소",
}

/** 목록에서 운영자가 고를 수 있는 신청서 상태 — 진행 경로 전부와 취소. 서버가 전이 순서를 강제하지 않는다. */
export const ORDER_STATUS_OPTIONS: PokemonOrderStatus[] = [...ORDER_STATUS_FLOW, "CANCELLED"]

/**
 * 몇 단계 중 몇 번째까지 왔는가. 첫 단계도 1로 센다 — 갓 시작한 것이 0% 로 비어 보이면 안 된다.
 * 취소는 진행 경로 밖이라 null 이다. 0 이 아니다.
 */
function stepIn<T extends string>(flow: T[], status: T): { step: number; total: number } | null {
    const index = flow.indexOf(status)
    return index < 0 ? null : { step: index + 1, total: flow.length }
}

export function roundStep(status: PokemonRoundStatus) {
    return stepIn(ROUND_STATUS_FLOW, status)
}

export function orderStep(status: PokemonOrderStatus) {
    return stepIn(ORDER_STATUS_FLOW, status)
}

export function percentOf(at: { step: number; total: number } | null): number | null {
    return at === null ? null : (at.step / at.total) * 100
}

export function isRoundActive(round: PokemonRound): boolean {
    return round.status !== "CANCELLED"
}

export function isOrderActive(order: PokemonOrder): boolean {
    return order.status !== "CANCELLED"
}

/** 모집중일 때만 신청을 받는다. */
export function acceptsOrders(round: PokemonRound): boolean {
    return round.status === "OPEN"
}

/* ===== 상품 ===== */

/** 상품 1코인당 가격(차수 통화). 코인 상품이 아니면 null — 0으로 나누지 않는다. */
export function pricePerCoin(product: PokemonProduct): number | null {
    if (product.coins <= 0) return null
    return product.price / product.coins
}

/* ===== 장바구니 ===== */

/** 상품 id -> 수량. 0 이나 음수는 고르지 않은 것으로 본다. */
export type PokemonSelection = Record<number, number>

export interface SelectionLine {
    product: PokemonProduct
    quantity: number
    lineAmount: number
    lineCoins: number
}

/** 고른 것만, 상품표 순서로. 상품표에 없는 id 는 조용히 버린다 — 내려간 상품이다. */
export function selectionLines(selection: PokemonSelection, products: PokemonProduct[]): SelectionLine[] {
    return products
        .map((product) => ({ product, quantity: Math.trunc(selection[product.id] ?? 0) }))
        .filter((line) => line.quantity > 0)
        .map((line) => ({
            product: line.product,
            quantity: line.quantity,
            lineAmount: round2(line.product.price * line.quantity),
            lineCoins: line.product.coins * line.quantity,
        }))
}

export interface PokemonQuote {
    /** 상품 합계 + 자유 입력 금액 (차수 통화). */
    totalAmount: number
    totalCoins: number
    /** round(totalAmount × rate) — 서버의 HALF_UP 과 같은 결과를 낸다. */
    estimatedItemsKrw: number
    donationKrw: number
    estimatedKrw: number
}

/**
 * 신청 화면의 미리보기. 오늘 환율(`currentRate.toKrw`)을 넘긴다.
 * 저장된 신청서의 예상 금액은 서버가 응답마다 새로 계산해 준다.
 */
export function quote(
    selection: PokemonSelection,
    products: PokemonProduct[],
    rate: number,
    donationKrw: number,
    extraAmount = 0,
): PokemonQuote {
    const lines = selectionLines(selection, products)
    const extra = Math.max(0, round2(extraAmount))
    const totalAmount = round2(lines.reduce((sum, line) => sum + line.lineAmount, extra))
    const totalCoins = lines.reduce((sum, line) => sum + line.lineCoins, 0)
    const estimatedItemsKrw = toKrw(totalAmount, rate)
    const donation = Math.max(0, Math.trunc(donationKrw))

    return { totalAmount, totalCoins, estimatedItemsKrw, donationKrw: donation, estimatedKrw: estimatedItemsKrw + donation }
}

/** 외화 -> 원. 서버의 BigDecimal HALF_UP 에 가깝지만 부동소수 때문에 .5 경계에서 1원 어긋날 수 있다 — 미리보기용이다. */
export function toKrw(amount: number, rate: number): number {
    return Math.round(amount * rate)
}

/* ===== 집계 ===== */

export interface PokemonRoundSummary {
    statusCounts: Record<PokemonOrderStatus, number>
    /** 취소를 뺀 신청서가 받아 가는 포켓코인 총합. */
    totalCoins: number
    /** 차수 통화 기준 총액 — 주최자가 스토어에서 실제로 써야 할 돈. */
    totalAmount: number
    /** 예상 이체 금액 총합 — 신청서별 예상 금액을 그대로 더한다(1원 오차 없음). */
    totalTransferKrw: number
    /** 기부금을 뺀 예상 상품값 합계. */
    totalItemsKrw: number
    /** 아직 입금이 확인되지 않은 금액과 그 사람들. */
    outstandingKrw: number
    awaitingDeposit: PokemonOrder[]
}

export function summarizeRound(orders: PokemonOrder[]): PokemonRoundSummary {
    const statusCounts: Record<PokemonOrderStatus, number> = {
        ORDERED: 0, DEPOSIT_CONFIRMED: 0, DELIVERED: 0, CANCELLED: 0,
    }
    for (const order of orders) {
        statusCounts[order.status] += 1
    }

    const active = orders.filter(isOrderActive)
    const awaitingDeposit = active.filter((order) => order.status === "ORDERED")

    return {
        statusCounts,
        totalCoins: active.reduce((sum, order) => sum + orderCoins(order), 0),
        totalAmount: round2(active.reduce((sum, order) => sum + order.totalAmount, 0)),
        totalTransferKrw: active.reduce((sum, order) => sum + order.estimatedKrw, 0),
        totalItemsKrw: active.reduce((sum, order) => sum + order.estimatedItemsKrw, 0),
        outstandingKrw: awaitingDeposit.reduce((sum, order) => sum + order.estimatedKrw, 0),
        awaitingDeposit,
    }
}

/**
 * 신청서가 받아 가는 포켓코인 총합. 서버가 totalCoins 를 내려주지만 항목에서 다시 세어 쓴다 —
 * 표에 찍히는 항목과 합계가 같은 출처에서 나와야 어긋나지 않는다.
 */
export function orderCoins(order: PokemonOrder): number {
    return order.items.reduce((sum, item) => sum + item.coins * item.quantity, 0)
}

/* ===== 페이징 ===== */

export const ORDERS_PER_PAGE = 10

export interface Page<T> {
    items: T[]
    /** 1부터. 범위를 벗어나면 가장 가까운 쪽으로 당겨 준다. */
    page: number
    totalPages: number
    total: number
}

/**
 * 목록을 페이지로 자른다. 신청서는 한 차수에 몇십 건 규모라 서버가 전부 내려주고
 * 여기서 자른다 — 페이지마다 조회하면 왕복이 늘고 집계(총액·미입금)는 어차피 전부 필요하다.
 *
 * <p>빈 목록도 1페이지다(0페이지는 없다). 범위를 벗어난 page 는 끝으로 당긴다 —
 * 마지막 항목을 지워 페이지가 줄었을 때 빈 화면이 뜨지 않게 한다.
 */
export function paginate<T>(items: T[], page: number, size = ORDERS_PER_PAGE): Page<T> {
    const total = items.length
    const totalPages = Math.max(1, Math.ceil(total / size))
    const current = Math.min(Math.max(1, Math.trunc(page) || 1), totalPages)
    const start = (current - 1) * size

    return { items: items.slice(start, start + size), page: current, totalPages, total }
}

/* ===== 권한 ===== */

/**
 * 신청서를 고치거나 지울 수 있는가 — 서버 판정과 같은 규칙이다.
 * 주최자는 언제든, 그 밖에는 본인이 낸 것만이고 입금이 확인되기 전까지다.
 *
 * <p>주인이 없는 신청서(회원 전용으로 바꾸기 전에 비회원이 낸 것)는 본인 확인이 성립하지
 * 않으므로 주최자만 손댈 수 있다.
 */
export function canModifyOrder(order: PokemonOrder, canManage: boolean): boolean {
    if (canManage) return true
    if (order.status !== "ORDERED") return false
    return order.mine
}

export function canDeleteComment(comment: PokemonComment, canManage: boolean): boolean {
    return canManage || comment.mine
}

/** 고치는 것은 본인만이다 — 주최자도 남의 댓글은 지우기만 하고 고치지는 못한다 (POKEMON-AC-38). */
export function canEditComment(comment: PokemonComment): boolean {
    return comment.mine
}

/* ===== 입력 검증 ===== */

export interface OrderFormInput {
    depositorName: string
    donation: string
    extraAmount: string
    selection: PokemonSelection
}

/** 빈 값은 0. 콤마는 허용하고, 숫자가 아니거나 음수면 null 이다. */
export function parseDonation(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isSafeInteger(value) ? value : null
}

/** 외화 금액. 소수 둘째 자리까지 — 스토어 가격이 ₹29.00 / $0.99 꼴이다. */
export function parseAmount(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isFinite(value) ? value : null
}

/** 빈 값은 0 — 패스·티켓은 코인이 없다. 음수·소수·문자는 null. */
export function parseCoins(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isSafeInteger(value) ? value : null
}

/** 신청은 로그인해야 한다 — 신청자 이름은 회원 닉네임이라 폼에 없다. */
export function validateOrderForm(input: OrderFormInput): string | null {
    if (input.depositorName.trim().length > 60) {
        return "입금자명은 60자까지 입력할 수 있습니다."
    }

    const extra = parseAmount(input.extraAmount)
    if (extra === null) {
        return "금액은 0 이상의 숫자로 입력해 주세요 (소수점 둘째 자리까지)."
    }

    const quantities = Object.values(input.selection).map((value) => Math.trunc(value))
    if (quantities.every((quantity) => quantity <= 0) && extra <= 0) {
        return "상품을 고르거나 금액을 직접 입력해 주세요."
    }
    if (quantities.some((quantity) => quantity > 99)) {
        return "상품 하나당 99개까지 신청할 수 있습니다."
    }
    if (parseDonation(input.donation) === null) {
        return "기부금은 0 이상의 정수로 입력해 주세요."
    }
    return null
}

export const MAX_COMMENT_LENGTH = 500

export interface CommentFormInput {
    content: string
}

/** 댓글도 로그인해야 쓴다 — 작성자는 회원 닉네임이다. */
export function validateComment(input: CommentFormInput): string | null {
    if (input.content.trim() === "") {
        return "댓글 내용을 입력해 주세요."
    }
    if (input.content.trim().length > MAX_COMMENT_LENGTH) {
        return `댓글은 ${MAX_COMMENT_LENGTH}자까지 쓸 수 있습니다.`
    }
    return null
}

export interface PokemonProductDraft {
    name: string
    currency: string
    price: string
    coins: string
    active: boolean
}

export function validateProductDraft(draft: PokemonProductDraft, currencies: string[]): string | null {
    if (draft.name.trim() === "") {
        return "상품 이름을 입력해 주세요."
    }
    if (draft.name.trim().length > 200) {
        return "상품 이름은 200자까지 입력할 수 있습니다."
    }
    if (!currencies.includes(draft.currency)) {
        return "지원하지 않는 통화입니다."
    }
    const price = parseAmount(draft.price)
    if (price === null || price <= 0) {
        return "가격은 0보다 큰 숫자로 입력해 주세요 (소수점 둘째 자리까지)."
    }
    if (parseCoins(draft.coins) === null) {
        return "포켓코인 수는 0 이상의 정수로 입력해 주세요 (패스·티켓은 0)."
    }
    return null
}

export interface PokemonRoundDraft {
    title: string
    currency: string
    bankAccount: string
    deadline: string
    memo: string
}

/**
 * @param hasDefaultAccount 주최자 기본 계좌가 있으면 차수에서 비워도 된다 — 서버가 그것을 쓴다.
 */
export function validateRoundDraft(
    draft: PokemonRoundDraft,
    currencies: string[],
    hasDefaultAccount = false,
): string | null {
    if (!currencies.includes(draft.currency)) {
        return "지원하지 않는 통화입니다."
    }
    if (draft.bankAccount.trim() === "" && !hasDefaultAccount) {
        return "입금받을 계좌를 적어 주세요. 상품 관리에서 기본 계좌를 정해 두면 자동으로 채워집니다."
    }
    if (draft.bankAccount.trim().length > 200) {
        return "계좌는 200자까지 입력할 수 있습니다."
    }
    if (draft.title.trim().length > 100) {
        return "제목은 100자까지 입력할 수 있습니다."
    }
    return null
}

/**
 * URL 에 들어가는 주소. 서버·DB 와 같은 규칙이고, Next 의 정적 경로와 겹치는 말은 막는다 —
 * /pokemon/products 가 주소인 사람에게 가려 버리기 때문이다.
 */
export const RESERVED_HANDLES = ["products", "orders", "new", "rounds", "hosts", "home"]

export function validateHandle(raw: string): string | null {
    const handle = raw.trim().toLowerCase()
    if (!/^[a-z0-9][a-z0-9-]{1,29}$/.test(handle)) {
        return "주소는 영소문자·숫자·하이픈으로 2~30자입니다. 첫 글자는 영소문자나 숫자여야 합니다."
    }
    if (RESERVED_HANDLES.includes(handle)) {
        return "이미 쓰이고 있는 주소입니다. 다른 주소를 골라 주세요."
    }
    return null
}

/* ===== 표시 ===== */

const CURRENCY_SYMBOLS: Record<string, string> = { INR: "₹", USD: "$", JPY: "¥", KRW: "₩" }

export function currencySymbol(currency: string): string {
    return CURRENCY_SYMBOLS[currency] ?? `${currency} `
}

/** 차수 통화 금액. 소수는 있을 때만 보인다 — ₹29 는 ₹29.00 보다 읽기 쉽다. */
export function formatAmount(value: number, currency: string): string {
    const fixed = Number.isInteger(value) ? value.toLocaleString("en-US") : value.toFixed(2)
    return `${currencySymbol(currency)}${fixed}`
}

export function formatKrw(value: number): string {
    return `${Math.round(value).toLocaleString("ko-KR")}원`
}

export function formatRate(rate: number, currency: string): string {
    return `${currencySymbol(currency)}1 = ${rate.toFixed(4)}원`
}

/**
 * "오늘 환율 기준 예상" 문구(POKEMON-AC-39). 날짜는 문자열에서 그대로 읽는다 —
 * Date 로 바꾸면 브라우저 시간대에 따라 날짜가 하루 밀릴 수 있다.
 */
export function rateNote(rate: { fetchedAt: string; stale: boolean }): string {
    const match = /^\d{4}-(\d{2})-(\d{2})/.exec(rate.fetchedAt)
    const when = match ? `${Number(match[1])}월 ${Number(match[2])}일` : ""
    if (rate.stale) {
        return when ? `마지막으로 확인된 환율 기준 예상 (${when})` : "마지막으로 확인된 환율 기준 예상"
    }
    return when ? `${when} 환율 기준 예상` : "오늘 환율 기준 예상"
}

export function formatCoins(coins: number): string {
    return `${coins.toLocaleString("ko-KR")} 포켓코인`
}

export function roundTitle(round: PokemonRound): string {
    return round.title ?? `${round.sequence}차 공동구매`
}

/** 소수 둘째 자리까지. 부동소수 누적 오차가 표시에 새지 않게 잘라 둔다. */
function round2(value: number): number {
    return Math.round(value * 100) / 100
}

/* ===== 마이페이지: 내가 참가한 공동구매 (POKEMON-AC-36) ===== */

/** `GET /api/back/pokemon/orders/me` 의 한 줄. 항목·댓글은 신청서 상세가 준다. */
export interface PokemonMyOrder {
    orderId: number
    roundId: number
    hostHandle: string
    roundSequence: number
    roundTitle: string | null
    currency: string
    roundStatus: PokemonRoundStatus
    orderStatus: PokemonOrderStatus
    /** 예상 이체 금액(상품값 환산 + 기부금) — 오늘 환율 기준. 그 통화의 환율을 모르면 null. */
    estimatedKrw: number | null
    createdAt: string
}

/** 차수 이름. 제목이 없으면 번호로 부른다 — 차수 화면의 `roundTitle` 과 같은 규칙이다. */
export function myOrderRoundTitle(order: PokemonMyOrder): string {
    return order.roundTitle ?? `${order.roundSequence}차 공동구매`
}

/** 그 차수로 가는 주소. 주최자 방의 `?round=N` 탭을 연다(POKEMON-AC-34). */
export function myOrderRoundHref(order: PokemonMyOrder): string {
    return `/pokemon/${encodeURIComponent(order.hostHandle)}?round=${order.roundSequence}`
}

/** 신청서 세부 화면 주소. */
export function myOrderHref(order: PokemonMyOrder): string {
    return `/pokemon/orders/${order.orderId}`
}

export interface PokemonMyOrderSummary {
    total: number
    /** 취소를 뺀, 아직 끝나지 않은(전달완료 전) 신청서 수. */
    inProgress: number
    delivered: number
    cancelled: number
    /** 입금확인 전(`ORDERED`) 신청서의 예상 금액 합 — 내가 아직 보내야 할 돈. */
    unpaidKrw: number
}

/**
 * 내 신청서의 현황. 취소는 진행 경로 밖이라 진행 중에도 미입금에도 세지 않는다
 * (POKEMON-AC-17 과 같은 이유). 통화가 달라도 예상 금액은 원화라 그대로 더한다.
 */
export function summarizeMyOrders(orders: PokemonMyOrder[]): PokemonMyOrderSummary {
    const summary: PokemonMyOrderSummary = { total: orders.length, inProgress: 0, delivered: 0, cancelled: 0, unpaidKrw: 0 }
    for (const order of orders) {
        if (order.orderStatus === "CANCELLED") {
            summary.cancelled += 1
        } else if (order.orderStatus === "DELIVERED") {
            summary.delivered += 1
        } else {
            summary.inProgress += 1
        }
        if (order.orderStatus === "ORDERED") {
            summary.unpaidKrw += order.estimatedKrw ?? 0
        }
    }
    return summary
}

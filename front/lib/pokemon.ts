// front/lib/pokemon.ts — 공동구매의 React-free 판단 로직.
// 컴포넌트에는 판단을 두지 않는다 (vitest 가 node 환경이라 컴포넌트는 검증 밖이다).
//
// 구조: 주최자가 차수(1차·2차·3차)를 열고 친구들이 거기에 신청한다.
// 통화·환율·계좌는 차수가 들고, 개인별로 다른 것(입금)은 신청서가 든다.

/* ===== 서버 계약 (app-mvc pokemon 도메인과 1:1) ===== */

/** 차수의 진행 단계. 주최자가 전체에 대해 하는 일이다. */
export type PokemonRoundStatus = "OPEN" | "CLOSED" | "PURCHASED" | "DELIVERED" | "CANCELLED"

/** 신청서의 진행 단계. 개인별로 다른 것만 여기 있다. */
export type PokemonOrderStatus = "ORDERED" | "DEPOSIT_CONFIRMED" | "DELIVERED" | "CANCELLED"

/** 환율을 어떻게 잡을지. 주최자가 차수를 열 때 고른다. */
export type PokemonRateMode = "FIXED" | "PER_ORDER"

export interface PokemonRound {
    id: number
    /** URL 조각 — /pokemon/{hostHandle}/{sequence} */
    hostHandle: string
    hostName: string
    sequence: number
    title: string | null
    /** ISO 4217. 이 차수의 상품·금액이 모두 이 통화다. */
    currency: string
    rateMode: PokemonRateMode
    /** 1 currency = N KRW. FIXED 일 때 쓰는 값. */
    quotedRate: number
    quotedAt: string
    bankAccount: string
    deadline: string | null
    status: PokemonRoundStatus
    /** 실제 결제 시점의 환율. 채워져 있으면 환차손익이 확정된 차수다. */
    settledRate: number | null
    settledAt: string | null
    memo: string | null
    createdAt: string
    /** 이 화면을 보는 사람이 이 차수를 주무를 수 있는가. 서버가 판단한다. */
    canManage: boolean
    orderCount: number
    transferKrwTotal: number
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
    /** 이 신청서에 적용된 환율. 차수 방식에 따라 차수 환율이거나 신청 시점 환율이다. */
    quotedRate: number
    quotedAt: string
    itemsKrw: number
    donationKrw: number
    /** itemsKrw + donationKrw — 신청자가 실제로 이체해야 하는 금액. */
    transferKrw: number
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

export const RATE_MODE_LABELS: Record<PokemonRateMode, string> = {
    FIXED: "차수 환율로 고정",
    PER_ORDER: "신청 시점 환율",
}

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

/** 결제까지 끝났으면 환율이 박혀 있고, 그때부터 환차손익은 움직이지 않는다. */
export function isRoundSettled(round: PokemonRound): boolean {
    return round.settledRate !== null && round.settledRate > 0
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
    itemsKrw: number
    donationKrw: number
    transferKrw: number
}

/**
 * 신청 화면의 미리보기. {@link rateFor} 가 고른 환율을 넘겨야 저장될 금액과 어긋나지 않는다.
 * 저장된 신청서는 서버가 박아 둔 금액을 그대로 쓴다.
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
    const itemsKrw = toKrw(totalAmount, rate)
    const donation = Math.max(0, Math.trunc(donationKrw))

    return { totalAmount, totalCoins, itemsKrw, donationKrw: donation, transferKrw: itemsKrw + donation }
}

/** 외화 -> 원. 서버가 BigDecimal.setScale(0, HALF_UP) 로 하는 것과 같은 반올림이다. */
export function toKrw(amount: number, rate: number): number {
    return Math.round(amount * rate)
}

/**
 * 이 차수에서 지금 신청하면 쓸 환율. FIXED 면 차수 환율, PER_ORDER 면 현재 환율이다 —
 * 서버의 PokemonRounds.rateFor 와 같은 규칙이라 미리보기와 저장 금액이 어긋나지 않는다.
 */
export function rateFor(round: PokemonRound, currentRate: number): number {
    return round.rateMode === "PER_ORDER" ? currentRate : round.quotedRate
}

/* ===== 환차손익 ===== */

/**
 * 손익을 재는 기준 환율. 차수가 결제까지 끝났으면 그때 박힌 환율로 확정되고,
 * 아직이면 현재 환율로 평가한다.
 */
export function effectiveRate(round: PokemonRound, currentRate: number): number {
    return isRoundSettled(round) ? (round.settledRate as number) : currentRate
}

/**
 * 신청서 한 건의 환차손익(원). 받아 둔 상품값에서 기준 환율로 실제로 드는 돈을 뺀 값이다.
 * 양수면 남고(이득), 음수면 모자란다.
 *
 * <p>기부금은 빼고 본다 — 환율과 무관한 순수 이득이라 섞으면 환차가 안 보인다.
 */
export function fxDelta(order: PokemonOrder, round: PokemonRound, currentRate: number): number {
    return order.itemsKrw - toKrw(order.totalAmount, effectiveRate(round, currentRate))
}

/* ===== 집계 ===== */

export interface PokemonRoundSummary {
    statusCounts: Record<PokemonOrderStatus, number>
    /** 취소를 뺀 신청서가 받아 가는 포켓코인 총합. */
    totalCoins: number
    /** 차수 통화 기준 총액 — 주최자가 스토어에서 실제로 써야 할 돈. */
    totalAmount: number
    /** 받아야 할 이체 금액 총합. */
    totalTransferKrw: number
    /** 기부금을 뺀 상품값 합계. */
    totalItemsKrw: number
    /** 아직 입금이 확인되지 않은 금액과 그 사람들. */
    outstandingKrw: number
    awaitingDeposit: PokemonOrder[]
    /** 차수 전체의 환차손익. */
    fxKrw: number
}

export function summarizeRound(
    orders: PokemonOrder[],
    round: PokemonRound,
    currentRate: number,
): PokemonRoundSummary {
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
        totalTransferKrw: active.reduce((sum, order) => sum + order.transferKrw, 0),
        totalItemsKrw: active.reduce((sum, order) => sum + order.itemsKrw, 0),
        outstandingKrw: awaitingDeposit.reduce((sum, order) => sum + order.transferKrw, 0),
        awaitingDeposit,
        fxKrw: active.reduce((sum, order) => sum + fxDelta(order, round, currentRate), 0),
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
    rateMode: PokemonRateMode
    /** 비우면 지금 환율을 그대로 쓴다. */
    quotedRate: string
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
    if (draft.rateMode === "FIXED" && draft.quotedRate.trim() !== "") {
        const rate = Number(draft.quotedRate.trim())
        if (!Number.isFinite(rate) || rate <= 0) {
            return "환율은 0보다 큰 숫자로 입력해 주세요."
        }
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

/** 부호를 항상 붙인다 — 손익은 방향이 값보다 중요하다. 0 은 부호 없이. */
export function formatSignedKrw(value: number): string {
    const rounded = Math.round(value)
    if (rounded === 0) return "0원"
    return `${rounded > 0 ? "+" : "−"}${Math.abs(rounded).toLocaleString("ko-KR")}원`
}

export function formatRate(rate: number, currency: string): string {
    return `${currencySymbol(currency)}1 = ${rate.toFixed(4)}원`
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

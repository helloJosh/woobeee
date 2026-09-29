// front/lib/pokemon.ts — 포켓코인 공동구매 탭의 React-free 판단 로직.
// 컴포넌트에는 판단을 두지 않는다 (vitest 가 node 환경이라 컴포넌트는 검증 밖이다).

/* ===== 서버 계약 (app-mvc pokemon 도메인과 1:1) ===== */

/** PokemonOrderStatus.java 와 같아야 한다. */
export type PokemonOrderStatus =
    | "ORDERED"
    | "PREPARING"
    | "DEPOSIT_CONFIRMED"
    | "DELIVERED"
    | "CANCELLED"

/**
 * 상품표 한 줄. 운영자가 인게임 상점을 보고 직접 관리한다(`/pokemon/products`) —
 * 패스는 달마다 바뀌므로 프론트에 박아 두지 않는다.
 */
export interface PokemonProduct {
    id: number
    /** 스토어에 적힌 이름 그대로. "550 PokéCoins", "Event Ticket" 같은 것. */
    name: string
    /** 포켓코인 수. 이벤트 티켓처럼 코인이 아닌 상품은 0. */
    coins: number
    /** 루피. 서버의 BigDecimal 이 JSON number 로 온다. */
    priceInr: number
}

/** 운영자 관리 화면이 보는 상품. 내려간 것까지 나오고 지울 수 있는지도 알려준다. */
export interface PokemonManagedProduct extends PokemonProduct {
    sortOrder: number
    active: boolean
    /** 신청서에 쓰인 적이 있으면 지울 수 없다 — 내리기만 된다. */
    inUse: boolean
    updatedAt: string | null
}

export interface PokemonProductDraft {
    name: string
    /** 입력 그대로의 문자열. 루피는 소수 둘째 자리까지. */
    priceInr: string
    /** 입력 그대로의 문자열. 패스·티켓은 0(빈 값도 0). */
    coins: string
    active: boolean
}

/** 문제가 있으면 사용자에게 보여줄 문구, 없으면 null. */
export function validateProductDraft(draft: PokemonProductDraft): string | null {
    if (draft.name.trim() === "") {
        return "상품 이름을 입력해 주세요."
    }
    if (draft.name.trim().length > 200) {
        return "상품 이름은 200자까지 입력할 수 있습니다."
    }
    const price = parseInr(draft.priceInr)
    if (price === null || price <= 0) {
        return "루피 가격은 0보다 큰 숫자로 입력해 주세요 (소수점 둘째 자리까지)."
    }
    const coins = parseCoins(draft.coins)
    if (coins === null) {
        return "포켓코인 수는 0 이상의 정수로 입력해 주세요 (패스·티켓은 0)."
    }
    return null
}

/** 빈 값은 0 — 패스·티켓은 코인이 없다. 음수·소수·문자는 null. */
export function parseCoins(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isSafeInteger(value) ? value : null
}

export interface PokemonOrderItem {
    /** 상품표 참조. 상품이 지워졌으면 null 일 수 있으므로 표시는 productName 으로 한다. */
    productId: number | null
    /** 신청 당시 이름·단가 스냅샷 — 상품표가 바뀌어도 과거 신청서는 이 값을 유지한다. */
    productName: string
    coins: number
    unitPriceInr: number
    quantity: number
}

export interface PokemonComment {
    id: number
    orderId: number
    authorName: string
    /** 비회원이 쓴 댓글이면 true. */
    guest: boolean
    /** 이 화면을 보는 사람이 쓴 댓글이면 true. 작성자의 회원 id 를 내보내지 않으려고 서버가 판단한다. */
    mine: boolean
    content: string
    createdAt: string
}

export interface PokemonOrder {
    id: number
    applicantName: string
    depositorName: string | null
    /** 비회원 신청이면 true. */
    guest: boolean
    /** 이 화면을 보는 사람이 낸 신청서면 true. 회원 id 를 내보내지 않으려고 서버가 판단한다. */
    mine: boolean
    status: PokemonOrderStatus
    totalInr: number
    /** 상품표에 없는 것을 위한 자유 입력 루피. 없으면 0. */
    extraInr: number
    /** 신청 시점에 박힌 환율. 이체 금액의 근거다. */
    quotedRate: number
    quotedAt: string
    itemsKrw: number
    donationKrw: number
    /** itemsKrw + donationKrw — 신청자가 실제로 이체해야 하는 금액. */
    transferKrw: number
    /** 실제 결제 시점의 환율. 채워져 있으면 환차손익이 확정된 건이다. */
    settledRate: number | null
    settledAt: string | null
    memo: string | null
    createdAt: string
    items: PokemonOrderItem[]
    /** 이 신청서로 받는 포켓코인 총합 — 서버가 항목에서 계산해 내려준다. */
    totalCoins: number
    /** 오래된 것부터. */
    comments: PokemonComment[]
}

export interface PokemonExchangeRate {
    /** 1 루피가 몇 원인지. */
    inrToKrw: number
    fetchedAt: string
    /** 외부 조회가 실패해 마지막 성공값을 쓰는 중이면 true. */
    stale: boolean
}

/** 세부 페이지가 받는 것 — 신청서 한 건 + 목록과 같은 부속. */
export interface PokemonOrderDetail {
    rate: PokemonExchangeRate
    order: PokemonOrder
    bankAccount: string
    canManage: boolean
}

export interface PokemonBoard {
    rate: PokemonExchangeRate
    products: PokemonProduct[]
    orders: PokemonOrder[]
    bankAccount: string
    /** 이 화면을 보는 사람이 진행 상태를 바꿀 수 있는 운영자인가. 서버가 판단한다. */
    canManage: boolean
}

/* ===== 상태 ===== */

/** 정상 진행 경로. CANCELLED 는 어느 단계에서든 빠져나가므로 여기 없다. */
export const STATUS_FLOW: PokemonOrderStatus[] = [
    "ORDERED",
    "PREPARING",
    "DEPOSIT_CONFIRMED",
    "DELIVERED",
]

export const STATUS_LABELS: Record<PokemonOrderStatus, string> = {
    ORDERED: "주문",
    PREPARING: "준비중",
    DEPOSIT_CONFIRMED: "입금확인",
    DELIVERED: "배달 완료",
    CANCELLED: "취소",
}

/**
 * 이 신청서가 네 단계 중 몇 번째까지 왔는가. 신청만 한 상태도 1단계를 밟은 것으로 센다 —
 * 진행도를 신청서마다 보여주므로, 갓 낸 신청서가 0% 로 비어 보이면 안 된다.
 *
 * <p>취소는 진행 경로 밖이라 null 이다. 0 이 아니다 — "진행이 없다" 와 "경로에 없다" 는 다르다.
 */
export function statusStep(status: PokemonOrderStatus): { step: number; total: number } | null {
    const index = STATUS_FLOW.indexOf(status)
    if (index < 0) return null
    return { step: index + 1, total: STATUS_FLOW.length }
}

/** 0~100. 취소는 null. */
export function statusPercent(status: PokemonOrderStatus): number | null {
    const at = statusStep(status)
    return at === null ? null : (at.step / at.total) * 100
}

export function isActive(order: PokemonOrder): boolean {
    return order.status !== "CANCELLED"
}

/** 준비중으로 넘어갔으면 환율이 박혀 있고, 그때부터 손익은 더 이상 움직이지 않는다. */
export function isSettled(order: PokemonOrder): boolean {
    return order.settledRate !== null && order.settledRate > 0
}

/* ===== 상품 가성비 ===== */

/**
 * 포켓코인 1개당 루피. 낮을수록 이득이다.
 *
 * <p>티어가 클수록 싼 것이 아니다 — 500포켓코인(₹149)은 100포켓코인(₹29) 다섯 개(₹145)보다
 * 비싸다. 상품 카드가 이 값을 그대로 보여주어 고르는 사람이 직접 비교할 수 있게 한다.
 */
export function pricePerCoin(product: PokemonProduct): number | null {
    if (product.coins <= 0) return null
    return product.priceInr / product.coins
}

/* ===== 장바구니 ===== */

/** 상품 id -> 수량. 0 이나 음수는 고르지 않은 것으로 본다. 상품이 매일 바뀌므로 이름이 아닌 id 로 건다. */
export type PokemonSelection = Record<number, number>

export interface SelectionLine {
    product: PokemonProduct
    quantity: number
    lineInr: number
    lineCoins: number
}

/** 고른 것만, 상품표 순서로. 상품표에 없는 id 는 조용히 버린다 — 스토어에서 내려간 상품이다. */
export function selectionLines(selection: PokemonSelection, products: PokemonProduct[]): SelectionLine[] {
    return products
        .map((product) => ({ product, quantity: Math.trunc(selection[product.id] ?? 0) }))
        .filter((line) => line.quantity > 0)
        .map((line) => ({
            product: line.product,
            quantity: line.quantity,
            lineInr: round2(line.product.priceInr * line.quantity),
            lineCoins: line.product.coins * line.quantity,
        }))
}

export interface PokemonQuote {
    /** 상품 합계 + 자유 입력 루피. */
    totalInr: number
    totalCoins: number
    /** round(totalInr × rate) — 서버의 HALF_UP 과 같은 결과를 낸다. */
    itemsKrw: number
    donationKrw: number
    /** 실제로 이체해야 하는 금액. */
    transferKrw: number
}

/** 신청 화면의 미리보기. 저장된 신청서는 서버가 박아 둔 금액을 그대로 쓴다. */
export function quote(
    selection: PokemonSelection,
    products: PokemonProduct[],
    rate: number,
    donationKrw: number,
    extraInr = 0,
): PokemonQuote {
    const lines = selectionLines(selection, products)
    const extra = Math.max(0, round2(extraInr))
    const totalInr = round2(lines.reduce((sum, line) => sum + line.lineInr, extra))
    const totalCoins = lines.reduce((sum, line) => sum + line.lineCoins, 0)
    const itemsKrw = toKrw(totalInr, rate)
    const donation = Math.max(0, Math.trunc(donationKrw))

    return { totalInr, totalCoins, itemsKrw, donationKrw: donation, transferKrw: itemsKrw + donation }
}

/** 루피 -> 원. 서버가 BigDecimal.setScale(0, HALF_UP) 로 하는 것과 같은 반올림이다. */
export function toKrw(inr: number, rate: number): number {
    return Math.round(inr * rate)
}

/* ===== 환차손익 ===== */

/**
 * 손익을 재는 기준 환율. 준비중으로 넘어간 건은 그때 박힌 환율로 확정되고,
 * 아직인 건은 현재 환율로 평가한다.
 */
export function effectiveRate(order: PokemonOrder, currentRate: number): number {
    return isSettled(order) ? (order.settledRate as number) : currentRate
}

/**
 * 환차손익(원). 신청자가 낸 상품값에서 그 환율로 실제로 드는 돈을 뺀 값이다.
 * 양수면 남고(이득), 음수면 모자란다(손해).
 *
 * <p>기부금은 빼고 본다 — 환율과 무관한 순수 이득이라 섞으면 환차가 안 보인다.
 */
export function fxDelta(order: PokemonOrder, currentRate: number): number {
    return order.itemsKrw - toKrw(order.totalInr, effectiveRate(order, currentRate))
}

/* ===== 집계 ===== */

export interface PokemonSummary {
    statusCounts: Record<PokemonOrderStatus, number>
    /** 취소를 뺀 신청서가 받아 가는 포켓코인 총합. */
    totalCoins: number
    /** 아직 입금이 확인되지 않은 금액 — 주문·준비중 단계. 입금확인은 세 번째 단계다. */
    outstandingKrw: number
    /** 미입금 명단 — 이 페이지를 만드는 실질적인 이유. */
    awaitingDeposit: PokemonOrder[]
    /**
     * 배달 완료 전 신청서의 이체 금액 합계 — 운영자가 아직 끝내지 못한 총액이다.
     * 취소는 처리할 것이 없으므로 뺀다.
     */
    inFlightKrw: number
    inFlightCount: number
    /** 배달 완료 전 신청서의 루피 합계 — 운영자가 인도 스토어에서 실제로 써야 할 돈이다. */
    inFlightInr: number
    /**
     * 배달 완료 전 신청서의 <b>기부금을 뺀</b> 원화 합계. 기부금은 상품값이 아니라서
     * 실제로 결제해야 할 금액과 섞이면 안 된다.
     */
    inFlightItemsKrw: number
}

/**
 * 목록 화면이 쓰는 집계. 환차손익과 진행도는 여기 없다 — <b>신청서마다</b> 보여주므로
 * 합계를 내지 않는다. {@link fxDelta} 와 {@link statusStep} 을 신청서 단위로 쓴다.
 */
export function summarize(orders: PokemonOrder[]): PokemonSummary {
    const statusCounts: Record<PokemonOrderStatus, number> = {
        ORDERED: 0, PREPARING: 0, DEPOSIT_CONFIRMED: 0, DELIVERED: 0, CANCELLED: 0,
    }
    for (const order of orders) {
        statusCounts[order.status] += 1
    }

    const active = orders.filter(isActive)
    const awaitingDeposit = active.filter((order) => order.status !== "DEPOSIT_CONFIRMED"
        && order.status !== "DELIVERED")
    const inFlight = active.filter((order) => order.status !== "DELIVERED")

    return {
        statusCounts,
        totalCoins: active.reduce((sum, order) => sum + orderCoins(order), 0),
        outstandingKrw: awaitingDeposit.reduce((sum, order) => sum + order.transferKrw, 0),
        awaitingDeposit,
        inFlightKrw: inFlight.reduce((sum, order) => sum + order.transferKrw, 0),
        inFlightCount: inFlight.length,
        inFlightInr: round2(inFlight.reduce((sum, order) => sum + order.totalInr, 0)),
        inFlightItemsKrw: inFlight.reduce((sum, order) => sum + order.itemsKrw, 0),
    }
}

/**
 * 신청서가 받아 가는 포켓코인 총합. 서버가 {@code totalCoins} 를 내려주지만 항목에서 다시 세어
 * 쓴다 — 표에 찍히는 항목과 합계가 같은 출처에서 나와야 어긋나지 않는다.
 */
export function orderCoins(order: PokemonOrder): number {
    return order.items.reduce((sum, item) => sum + item.coins * item.quantity, 0)
}

/* ===== 신청 폼 검증 ===== */

export interface OrderFormInput {
    /** 비회원만 쓴다. 로그인 신청은 서버가 닉네임으로 덮는다. */
    applicantName: string
    depositorName: string
    /** 입력 그대로의 문자열. 빈 값은 기부 없음이다. */
    donation: string
    /** 입력 그대로의 문자열. 상품표에 없는 것을 신청할 때 쓴다. 빈 값은 0. */
    extraInr: string
    selection: PokemonSelection
}

/**
 * 자유 입력 루피. 빈 값은 0, 소수 둘째 자리까지 허용한다 — 스토어 가격이 ₹29.00 꼴이라
 * 소수를 막으면 그대로 옮겨 적을 수가 없다. 음수와 숫자 아닌 값은 null.
 */
export function parseInr(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isFinite(value) ? value : null
}

/** 빈 값은 0. 콤마는 허용하고, 숫자가 아니거나 음수면 null 이다. */
export function parseDonation(raw: string): number | null {
    const trimmed = raw.trim().replace(/,/g, "")
    if (trimmed === "") return 0
    if (!/^\d+$/.test(trimmed)) return null
    const value = Number(trimmed)
    return Number.isSafeInteger(value) ? value : null
}

/** 문제가 있으면 사용자에게 보여줄 문구, 없으면 null. */
export function validateOrderForm(input: OrderFormInput, loggedIn: boolean): string | null {
    if (!loggedIn && input.applicantName.trim() === "") {
        return "이름을 입력해 주세요."
    }
    if (input.applicantName.trim().length > 60 || input.depositorName.trim().length > 60) {
        return "이름은 60자까지 입력할 수 있습니다."
    }

    const extra = parseInr(input.extraInr)
    if (extra === null) {
        return "루피는 0 이상의 숫자로 입력해 주세요 (소수점 둘째 자리까지)."
    }

    const quantities = Object.values(input.selection).map((value) => Math.trunc(value))
    // 상품을 고르지 않았어도 루피를 직접 적었으면 살 것이 있다.
    if (quantities.every((quantity) => quantity <= 0) && extra <= 0) {
        return "상품을 고르거나 루피를 직접 입력해 주세요."
    }
    if (quantities.some((quantity) => quantity > 99)) {
        return "상품 하나당 99개까지 신청할 수 있습니다."
    }
    if (parseDonation(input.donation) === null) {
        return "기부금은 0 이상의 정수로 입력해 주세요."
    }

    return null
}

/* ===== 댓글 ===== */

export const MAX_COMMENT_LENGTH = 500

export interface CommentFormInput {
    /** 비회원만 쓴다. 로그인 댓글은 서버가 닉네임으로 덮는다. */
    authorName: string
    content: string
}

/** 문제가 있으면 사용자에게 보여줄 문구, 없으면 null. */
export function validateComment(input: CommentFormInput, loggedIn: boolean): string | null {
    if (!loggedIn && input.authorName.trim() === "") {
        return "이름을 입력해 주세요."
    }
    if (input.content.trim() === "") {
        return "댓글 내용을 입력해 주세요."
    }
    if (input.content.trim().length > MAX_COMMENT_LENGTH) {
        return `댓글은 ${MAX_COMMENT_LENGTH}자까지 쓸 수 있습니다.`
    }
    return null
}

/**
 * 신청서를 <b>고치거나 지울 수 있는가</b> — 서버 판정과 같은 규칙이다. 수정과 삭제는
 * 같은 규칙을 쓴다: 둘 다 남의 신청서를 건드리는 일이고, 준비가 시작되면 둘 다 막힌다.
 *
 * <ul>
 *   <li>운영자는 언제든
 *   <li>회원이 낸 신청서는 <b>그 회원만</b>
 *   <li>비회원이 낸 신청서는 주인이 없으므로 <b>누구나</b> — 본인 확인 수단이 없는데 막아 두면
 *       잘못 낸 신청서를 아무도 거두지 못한다
 * </ul>
 *
 * <p>단 준비가 시작된 뒤로는 운영자만 지운다. 이미 물건을 사러 갔기 때문이다.
 * 여기는 버튼을 그릴지 말지일 뿐이고, 진짜 방어는 서버가 한다.
 */
export function canModifyOrder(order: PokemonOrder, canManage: boolean): boolean {
    if (canManage) return true
    if (order.status !== "ORDERED") return false
    return order.guest || order.mine
}

/**
 * 운영자이거나 본인이 쓴 댓글일 때만 지울 수 있다 — 서버 판정과 같은 규칙이다.
 * 여기는 버튼을 그릴지 말지일 뿐이고, 진짜 방어는 서버가 한다.
 */
export function canDeleteComment(comment: PokemonComment, canManage: boolean): boolean {
    return canManage || comment.mine
}

/* ===== 표시 ===== */

export function formatKrw(value: number): string {
    return `${Math.round(value).toLocaleString("ko-KR")}원`
}

/** 부호를 항상 붙인다 — 손익은 방향이 값보다 중요하다. 0 은 부호 없이. */
export function formatSignedKrw(value: number): string {
    const rounded = Math.round(value)
    if (rounded === 0) return "0원"
    const sign = rounded > 0 ? "+" : "−"
    return `${sign}${Math.abs(rounded).toLocaleString("ko-KR")}원`
}

export function formatInr(value: number): string {
    return `₹${value.toLocaleString("en-IN", { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`
}

export function formatRate(rate: number): string {
    return `₹1 = ${rate.toFixed(4)}원`
}

export function formatCoins(coins: number): string {
    return `${coins.toLocaleString("ko-KR")} 포켓코인`
}

/** 루피 금액은 소수 둘째 자리까지다. 부동소수 누적 오차가 표시에 새지 않게 잘라 둔다. */
function round2(value: number): number {
    return Math.round(value * 100) / 100
}

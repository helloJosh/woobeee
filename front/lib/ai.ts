/**
 * AI 요청하기(docs/ai/PRD.md) 의 판단 로직. 컴포넌트가 아니라 여기에 두고 lib/ai.test.ts 가 고정한다 —
 * 서버와 같은 한도로 이미지를 미리 거르고(AI-AC-18), 언제 폴링하고 언제 제출을 막는지 정한다.
 */

export type AiStatus = "RECEIVED" | "PROCESSING" | "DRAFTED" | "APPLIED" | "FAILED" | "DEAD"

export type AiActionType = "ROUND_PRODUCTS" | "TASK"

export interface AiAction {
    id: number
    type: AiActionType
    status: "PENDING" | "APPLIED" | "REJECTED"
    /** type 마다 모양이 다르다 — 상품은 {roundId, name, price, coins}, 할 일은 할 일 필드들. */
    payload: Record<string, unknown>
}

export interface AiRequest {
    id: string
    status: AiStatus
    text: string | null
    imageUrls: string[]
    summary: string | null
    lastError: string | null
    attempts: number
    actions: AiAction[]
    createdAt: string
}

/** 서버(AiProperties)와 같은 한도. 어긋나면 화면은 통과시키고 서버가 400 으로 거절한다. */
export const MAX_IMAGES = 5
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024
export const ALLOWED_IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp"]

/** 진행 중 요청을 다시 읽어 오는 간격. */
export const POLL_INTERVAL_MS = 3000

export type AiImageError = "tooMany" | "tooLarge" | "unsupported"

/** 서버와 같은 순서로 본다: 장수 → 장마다 형식 → 장마다 크기. 문제가 없으면 null. */
export function validateImages(files: { type: string; size: number }[]): AiImageError | null {
    if (files.length > MAX_IMAGES) {
        return "tooMany"
    }
    for (const file of files) {
        if (!ALLOWED_IMAGE_TYPES.includes(file.type.toLowerCase())) {
            return "unsupported"
        }
        if (file.size > MAX_IMAGE_BYTES) {
            return "tooLarge"
        }
    }
    return null
}

export function imageErrorMessage(error: AiImageError): string {
    switch (error) {
        case "tooMany":
            return `이미지는 최대 ${MAX_IMAGES}장까지 올릴 수 있습니다.`
        case "tooLarge":
            return "이미지는 한 장에 5MB까지입니다."
        case "unsupported":
            return "png, jpeg, webp 이미지만 올릴 수 있습니다."
    }
}

const STATUS_LABELS: Record<AiStatus, string> = {
    RECEIVED: "접수했어요 — 차례를 기다리는 중",
    PROCESSING: "AI 가 읽는 중",
    DRAFTED: "제안이 준비됐어요",
    APPLIED: "모두 처리했어요",
    FAILED: "문제가 생겨 다시 시도하는 중",
    DEAD: "실패했어요",
}

export function statusLabel(status: AiStatus): string {
    return STATUS_LABELS[status]
}

/** 워커 쪽 처리가 끝난 상태 — 더 기다릴 것이 없다. */
export function isTerminal(status: AiStatus): boolean {
    return status === "DRAFTED" || status === "APPLIED" || status === "DEAD"
}

/** 진행 중(= 서버의 "사용자당 1건" 제한에 걸리는 상태)이면 계속 읽어 온다. */
export function shouldPoll(status: AiStatus): boolean {
    return status === "RECEIVED" || status === "PROCESSING" || status === "FAILED"
}

/** 진행 중인 요청이 있으면 새 요청을 못 낸다(AI-AC-02). 서버도 409 로 막지만 화면이 먼저 막는다. */
export function canSubmit(latest: { status: AiStatus } | null): boolean {
    return latest === null || !shouldPoll(latest.status)
}

/** 제안이 모두 결정됐는가. 제안이 하나도 없으면 결정할 것이 없으니 true("제안 없음"). */
export function allDecided(actions: AiAction[]): boolean {
    return actions.every((action) => action.status !== "PENDING")
}

/** 승인 목록에 보이는 한 줄. 상품 payload 에는 통화가 없으므로(차수가 정한다) 가격은 숫자만 보인다. */
export function describeAction(action: AiAction): string {
    const p = action.payload
    if (action.type === "ROUND_PRODUCTS") {
        const coins = Number(p.coins ?? 0)
        return [
            `차수 #${p.roundId}에 상품 추가`,
            String(p.name),
            `가격 ${p.price}`,
            ...(coins > 0 ? [`포켓코인 ${coins}개`] : []),
        ].join(" · ")
    }

    const start = p.startDate ? `${p.startDate}${p.startTime ? ` ${p.startTime}` : ""}` : null
    const range = start && p.endDate && p.endDate !== p.startDate ? `${start} ~ ${p.endDate}` : start
    return ["할 일 추가", String(p.name), ...(range ? [range] : [])].join(" · ")
}

"use client"

import { Progress } from "@/components/ui/progress"
import {
    ORDER_STATUS_FLOW,
    ORDER_STATUS_LABELS,
    ROUND_STATUS_FLOW,
    ROUND_STATUS_LABELS,
    orderStep,
    percentOf,
    roundStep,
    type PokemonOrderStatus,
    type PokemonRoundStatus,
} from "@/lib/pokemon"

/** 목록 행에 들어가는 한 줄짜리 진행도 — 현재 단계 이름과 N/총. */
export function ProgressBarLine({
    label,
    at,
}: {
    label: string
    at: { step: number; total: number } | null
}) {
    if (at === null) {
        return <p className="text-xs text-muted-foreground">취소됨</p>
    }
    return (
        <div className="space-y-1">
            <div className="flex items-baseline justify-between text-xs">
                <span className="font-medium">{label}</span>
                <span className="text-muted-foreground">
                    {at.step}/{at.total}
                </span>
            </div>
            <Progress value={percentOf(at) ?? 0} className="h-1.5" />
        </div>
    )
}

export function RoundProgressBar({ status }: { status: PokemonRoundStatus }) {
    return <ProgressBarLine label={ROUND_STATUS_LABELS[status]} at={roundStep(status)} />
}

export function OrderProgressBar({ status }: { status: PokemonOrderStatus }) {
    return <ProgressBarLine label={ORDER_STATUS_LABELS[status]} at={orderStep(status)} />
}

/** 세부 화면의 단계 표시 — 지나온 단계를 채워서 어디까지 왔는지 한눈에 보이게 한다. */
function Steps({ flow, labels, currentIndex }: {
    flow: string[]
    labels: Record<string, string>
    currentIndex: number
}) {
    return (
        <div className="space-y-2">
            <div className="flex items-center">
                {flow.map((step, index) => (
                    <div key={step} className="flex flex-1 items-center last:flex-none">
                        <span
                            className={`h-2.5 w-2.5 shrink-0 rounded-full ${
                                index <= currentIndex ? "bg-primary" : "bg-muted-foreground/30"
                            }`}
                            aria-hidden
                        />
                        {index < flow.length - 1 && (
                            <span
                                className={`mx-1 h-0.5 flex-1 ${
                                    index < currentIndex ? "bg-primary" : "bg-muted-foreground/30"
                                }`}
                                aria-hidden
                            />
                        )}
                    </div>
                ))}
            </div>
            <div className="flex justify-between gap-1 text-[11px] leading-tight">
                {flow.map((step, index) => (
                    <span
                        key={step}
                        className={
                            index === currentIndex
                                ? "font-semibold"
                                : index < currentIndex
                                  ? "text-muted-foreground"
                                  : "text-muted-foreground/60"
                        }
                    >
                        {labels[step]}
                    </span>
                ))}
            </div>
        </div>
    )
}

export function RoundProgressSteps({ status }: { status: PokemonRoundStatus }) {
    const at = roundStep(status)
    return (
        <div className="space-y-2">
            <Steps flow={ROUND_STATUS_FLOW} labels={ROUND_STATUS_LABELS}
                   currentIndex={at === null ? -1 : at.step - 1} />
            {at === null && (
                <p className="text-xs text-muted-foreground">취소된 공동구매입니다.</p>
            )}
        </div>
    )
}

export function OrderProgressSteps({ status }: { status: PokemonOrderStatus }) {
    const at = orderStep(status)
    return (
        <div className="space-y-2">
            <Steps flow={ORDER_STATUS_FLOW} labels={ORDER_STATUS_LABELS}
                   currentIndex={at === null ? -1 : at.step - 1} />
            {at === null && (
                <p className="text-xs text-muted-foreground">
                    취소된 신청서입니다 — 진행 경로에서 빠집니다.
                </p>
            )}
        </div>
    )
}

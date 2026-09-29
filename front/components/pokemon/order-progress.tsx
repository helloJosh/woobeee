"use client"

import { Progress } from "@/components/ui/progress"
import { STATUS_FLOW, STATUS_LABELS, statusPercent, statusStep, type PokemonOrderStatus } from "@/lib/pokemon"

/** 목록 행에 들어가는 한 줄짜리 진행도 — 현재 단계 이름과 N/4. */
export function OrderProgressBar({ status }: { status: PokemonOrderStatus }) {
    const at = statusStep(status)
    if (at === null) {
        return <p className="text-xs text-muted-foreground">취소된 신청서</p>
    }

    return (
        <div className="space-y-1">
            <div className="flex items-baseline justify-between text-xs">
                <span className="font-medium">{STATUS_LABELS[status]}</span>
                <span className="text-muted-foreground">
                    {at.step}/{at.total}
                </span>
            </div>
            <Progress value={statusPercent(status) ?? 0} className="h-1.5" />
        </div>
    )
}

/** 세부 페이지의 네 단계 표시 — 지나온 단계를 채워서 어디까지 왔는지 한눈에 보이게 한다. */
export function OrderProgressSteps({ status }: { status: PokemonOrderStatus }) {
    const at = statusStep(status)
    const currentIndex = at === null ? -1 : at.step - 1

    return (
        <div className="space-y-2">
            <div className="flex items-center">
                {STATUS_FLOW.map((step, index) => {
                    const reached = index <= currentIndex
                    return (
                        <div key={step} className="flex flex-1 items-center last:flex-none">
                            <span
                                className={`h-2.5 w-2.5 shrink-0 rounded-full ${
                                    reached ? "bg-primary" : "bg-muted-foreground/30"
                                }`}
                                aria-hidden
                            />
                            {index < STATUS_FLOW.length - 1 && (
                                <span
                                    className={`mx-1 h-0.5 flex-1 ${
                                        index < currentIndex ? "bg-primary" : "bg-muted-foreground/30"
                                    }`}
                                    aria-hidden
                                />
                            )}
                        </div>
                    )
                })}
            </div>

            <div className="flex justify-between gap-1 text-[11px] leading-tight">
                {STATUS_FLOW.map((step, index) => (
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
                        {STATUS_LABELS[step]}
                    </span>
                ))}
            </div>

            {at === null && (
                <p className="text-xs text-muted-foreground">
                    취소된 신청서입니다 — 진행 경로에서 빠집니다.
                </p>
            )}
        </div>
    )
}

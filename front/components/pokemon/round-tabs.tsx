"use client"

import { Folder, FolderOpen } from "lucide-react"
import { ROUND_STATUS_LABELS, roundTitle, type PokemonRound } from "@/lib/pokemon"

/**
 * 차수를 폴더 카드처럼 늘어놓은 탭. 누르면 옮겨 다니지 않고 <b>그 자리에서</b> 내용이 바뀐다.
 */
export default function RoundTabs({
    rounds,
    selected,
    onSelect,
}: {
    rounds: PokemonRound[]
    /** 고른 차수 번호(문자열). */
    selected: string
    onSelect: (next: string) => void
}) {
    const card = (active: boolean, cancelled = false) =>
        `rounded-md border p-3 text-left transition-colors ${
            active ? "border-primary bg-accent" : "hover:bg-accent/50"
        } ${cancelled ? "opacity-50" : ""}`

    return (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
            {rounds.map((round) => {
                const active = selected === String(round.sequence)
                const Icon = active ? FolderOpen : Folder

                return (
                    <button
                        key={round.id}
                        type="button"
                        onClick={() => onSelect(String(round.sequence))}
                        aria-current={active ? "page" : undefined}
                        className={card(active, round.status === "CANCELLED")}
                    >
                        <div className="flex items-center gap-2">
                            <Icon className={`h-4 w-4 shrink-0 ${active ? "" : "text-muted-foreground"}`} />
                            <span className={`truncate text-sm ${active ? "font-semibold" : ""}`}>
                                {roundTitle(round)}
                            </span>
                        </div>
                        <div className="mt-1 flex items-baseline justify-between gap-1 text-xs text-muted-foreground">
                            <span>{ROUND_STATUS_LABELS[round.status]} · {round.currency}</span>
                            <span>{round.orderCount}건</span>
                        </div>
                    </button>
                )
            })}
        </div>
    )
}

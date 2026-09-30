"use client"

import Link from "next/link"
import { Folder, FolderOpen } from "lucide-react"
import { ROUND_STATUS_LABELS, roundTitle, type PokemonRound } from "@/lib/pokemon"

/**
 * 한 주최자의 차수들을 폴더 카드처럼 늘어놓는다. 누르면 그 차수 화면으로 간다 —
 * 주소가 유지되므로 링크를 그대로 공유할 수 있다.
 */
export default function RoundTabs({
    rounds,
    currentId,
}: {
    rounds: PokemonRound[]
    currentId: number
}) {
    if (rounds.length === 0) {
        return null
    }

    return (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
            {rounds.map((round) => {
                const current = round.id === currentId
                const Icon = current ? FolderOpen : Folder

                return (
                    <Link
                        key={round.id}
                        href={`/pokemon/${round.hostHandle}/${round.sequence}`}
                        aria-current={current ? "page" : undefined}
                        className={`rounded-md border p-3 transition-colors ${
                            current
                                ? "border-primary bg-accent"
                                : "hover:bg-accent/50"
                        } ${round.status === "CANCELLED" ? "opacity-50" : ""}`}
                    >
                        <div className="flex items-center gap-2">
                            <Icon className={`h-4 w-4 shrink-0 ${current ? "" : "text-muted-foreground"}`} />
                            <span className={`truncate text-sm ${current ? "font-semibold" : ""}`}>
                                {roundTitle(round)}
                            </span>
                        </div>
                        <div className="mt-1 flex items-baseline justify-between gap-1 text-xs text-muted-foreground">
                            <span>{ROUND_STATUS_LABELS[round.status]}</span>
                            <span>{round.orderCount}건</span>
                        </div>
                    </Link>
                )
            })}
        </div>
    )
}

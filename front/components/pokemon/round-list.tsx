"use client"

import Link from "next/link"
import { RoundProgressBar } from "@/components/pokemon/progress-steps"
import { formatKrw, formatRate, roundTitle, type PokemonRound } from "@/lib/pokemon"

/** 차수 목록 한 벌 — 첫 화면과 주최자 화면이 함께 쓴다. */
export default function RoundList({
    rounds,
    showHost = false,
    emptyMessage,
}: {
    rounds: PokemonRound[]
    /** 첫 화면에서는 누가 여는지가 중요하고, 주최자 화면에서는 이미 안다. */
    showHost?: boolean
    emptyMessage: string
}) {
    if (rounds.length === 0) {
        return <p className="py-8 text-center text-sm text-muted-foreground">{emptyMessage}</p>
    }

    return (
        <div className="space-y-2">
            {rounds.map((round) => (
                <Link
                    key={round.id}
                    href={`/pokemon/${round.hostHandle}/${round.sequence}`}
                    className={`block rounded-md border p-3 transition-colors hover:bg-accent ${
                        round.status === "CANCELLED" ? "opacity-50" : ""
                    }`}
                >
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <span className="font-medium">
                            {showHost && (
                                <span className="text-muted-foreground">{round.hostName} · </span>
                            )}
                            {roundTitle(round)}
                        </span>
                        <span className="text-xs text-muted-foreground">
                            {round.currency} · {formatRate(round.quotedRate, round.currency)}
                        </span>
                    </div>

                    <div className="mt-3">
                        <RoundProgressBar status={round.status} />
                    </div>

                    <div className="mt-3 flex flex-wrap items-baseline justify-between gap-2 text-sm">
                        <span className="text-muted-foreground">신청 {round.orderCount}건</span>
                        <span className="font-semibold">{formatKrw(round.transferKrwTotal)}</span>
                        {round.deadline !== null && (
                            <span className="text-xs text-muted-foreground">
                                마감 {new Date(round.deadline).toLocaleDateString("ko-KR")}
                            </span>
                        )}
                    </div>
                </Link>
            ))}
        </div>
    )
}

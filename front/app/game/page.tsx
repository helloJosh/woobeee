"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { assertNever, createGameRoom } from "@/lib/game-hub"
import { useAuth } from "@/hooks/use-auth"
import type { GameType } from "@/lib/types"

const GAMES: { type: GameType; path: string; title: string; blurb: string; players: string }[] = [
    {
        type: "OMOK",
        path: "omok",
        title: "오목",
        blurb: "15×15 렌주룰. 흑에게 삼삼·사사·장목 금수가 적용됩니다.",
        players: "1:1",
    },
    {
        type: "DODGE",
        path: "dodge",
        title: "장애물피하기",
        blurb: "위에서 떨어지는 장애물을 피하세요. 마지막 생존자가 승리합니다.",
        players: "최대 8인",
    },
]

export default function GameHubPage() {
    const router = useRouter()
    const { isAuthenticated, loading } = useAuth()
    const [creating, setCreating] = useState<GameType | null>(null)
    const [error, setError] = useState<string | null>(null)

    const createRoom = async (gameType: GameType, path: string) => {
        setError(null)
        setCreating(gameType)

        const outcome = await createGameRoom(gameType, path, isAuthenticated)
        switch (outcome.kind) {
            case "redirect-to-login":
                setCreating(null)
                router.push("/login")
                break
            case "navigate":
                // creating을 그대로 두고 스피너를 보여준 채 다음 페이지로 이동한다.
                router.push(outcome.path)
                break
            case "error":
                setCreating(null)
                setError(outcome.message)
                break
            default:
                assertNever(outcome)
        }
    }

    return (
        <main className="mx-auto w-full max-w-4xl px-4 py-10">
            <div className="space-y-2">
                <h1 className="text-3xl font-semibold">게임</h1>
                <p className="text-sm text-muted-foreground">
                    방을 만들고 초대 링크를 보내면 친구가 로그인 없이도 참가할 수 있습니다.
                </p>
            </div>

            <div className="mt-8 grid gap-4 sm:grid-cols-2">
                {GAMES.map((game) => (
                    <div key={game.type} className="flex flex-col rounded-lg border p-6">
                        <div className="flex items-baseline justify-between">
                            <h2 className="text-xl font-medium">{game.title}</h2>
                            <span className="text-xs text-muted-foreground">{game.players}</span>
                        </div>
                        <p className="mt-2 flex-1 text-sm text-muted-foreground">{game.blurb}</p>
                        <Button
                            className="mt-6"
                            disabled={loading || creating !== null}
                            onClick={() => createRoom(game.type, game.path)}
                        >
                            {creating === game.type ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
                            방 만들기
                        </Button>
                    </div>
                ))}
            </div>

            {error ? (
                <Alert variant="destructive" className="mt-6">
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            ) : null}

            {!loading && !isAuthenticated ? (
                <p className="mt-6 text-sm text-muted-foreground">
                    방을 만들려면{" "}
                    <Link href="/login" className="text-primary hover:underline">
                        로그인
                    </Link>
                    이 필요합니다. 초대 링크로 참가할 때는 닉네임만 있으면 됩니다.
                </p>
            ) : null}
        </main>
    )
}

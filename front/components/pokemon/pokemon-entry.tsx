"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { AlertTriangle, ArrowRight, Coins, Plus, Settings } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Field } from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import { validateHandle, type PokemonHome } from "@/lib/pokemon"

/**
 * 공동구매로 들어가는 문. 홈(/)과 /pokemon 이 함께 쓴다.
 *
 * <p>공동구매는 주최자 주소({@code /pokemon/{handle}})로 찾아 들어간다. 남이 연 것까지
 * 늘어놓지 않는다 — 아는 사람의 주소를 치고 들어가는 방식이다.
 */
export default function PokemonEntry() {
    const router = useRouter()
    const [home, setHome] = useState<PokemonHome | null>(null)
    const [handle, setHandle] = useState("")
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        // 못 불러와도 화면은 쓸 수 있어야 한다 — 주소를 치고 들어가는 것이 본체다.
        pokemonAPI.getHome().then(setHome).catch(() => setHome(null))
    }, [])

    const go = () => {
        const problem = validateHandle(handle)
        if (problem !== null) {
            setError(problem)
            return
        }
        setError(null)
        router.push(`/pokemon/${handle.trim().toLowerCase()}`)
    }

    return (
        <main className="mx-auto max-w-xl space-y-6 px-4 py-12">
            <header className="space-y-1 text-center">
                <h1 className="flex items-center justify-center gap-2 text-2xl font-bold">
                    <Coins className="h-6 w-6" /> 공동구매
                </h1>
                <p className="text-sm text-muted-foreground">
                    주최자의 주소를 입력하면 그 사람이 여는 공동구매로 갑니다.
                </p>
            </header>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">주최자 주소로 들어가기</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                    <Field label="주소">
                        <Input
                            value={handle}
                            onChange={(event) => {
                                setHandle(event.target.value)
                                setError(null)
                            }}
                            onKeyDown={(event) => {
                                if (event.key === "Enter") go()
                            }}
                            placeholder="예) hs"
                            maxLength={30}
                            autoFocus
                        />
                    </Field>
                    <p className="text-xs text-muted-foreground">
                        /pokemon/<b>{handle.trim().toLowerCase() || "주소"}</b>
                    </p>

                    {error !== null && (
                        <Alert variant="destructive">
                            <AlertTriangle className="h-4 w-4" />
                            <AlertDescription>{error}</AlertDescription>
                        </Alert>
                    )}

                    <Button onClick={go} className="w-full">
                        들어가기 <ArrowRight className="ml-1 h-4 w-4" />
                    </Button>
                </CardContent>
            </Card>

            {home !== null && home.loggedIn && (
                <div className="flex flex-wrap items-center justify-center gap-2">
                    {home.myHandle !== null ? (
                        <>
                            <Button asChild variant="outline">
                                <Link href={`/pokemon/${home.myHandle}`}>내 공동구매</Link>
                            </Button>
                            <Button asChild variant="outline">
                                <Link href="/pokemon/products">
                                    <Settings className="mr-1 h-4 w-4" /> 내 상품표
                                </Link>
                            </Button>
                        </>
                    ) : null}
                    <Button asChild>
                        <Link href="/pokemon/new">
                            <Plus className="mr-1 h-4 w-4" /> 공동구매 열기
                        </Link>
                    </Button>
                </div>
            )}

            {home !== null && !home.loggedIn && (
                <p className="text-center text-xs text-muted-foreground">
                    공동구매를 열거나 신청하려면 로그인이 필요합니다.
                </p>
            )}
        </main>
    )
}

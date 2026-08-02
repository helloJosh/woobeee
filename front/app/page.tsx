"use client"

import Link from "next/link"
import { Button } from "@/components/ui/button"

export default function HomePage() {
    return (
        <main className="mx-auto flex min-h-[70vh] w-full max-w-3xl flex-col items-center justify-center px-4 text-center">
            <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">woobeee</h1>
            <p className="mt-4 text-balance text-muted-foreground">
                친구와 같이 오목과 장애물피하기를 하고, 기술블로그를 읽는 곳입니다.
            </p>

            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
                <Button asChild size="lg">
                    <Link href="/game">게임하러 가기</Link>
                </Button>
                <Button asChild variant="outline" size="lg">
                    <Link href="/blog">기술블로그</Link>
                </Button>
            </div>
        </main>
    )
}

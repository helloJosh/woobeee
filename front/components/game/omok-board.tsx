"use client"

import type { OmokPlacement } from "@/lib/omok-play"

// 판 위의 값 타입은 lib/omok-play.ts 에 있다(리듀서가 같은 타입으로 상태를 만든다).
// 여기서 다시 내보내는 것은 화면 쪽에서 <OmokBoard /> 만 import 하고도 쓸 수 있게 하려는 것뿐이다.
export type { OmokPlacement, OmokStone } from "@/lib/omok-play"

interface OmokBoardProps {
    size: number
    placements: OmokPlacement[]
    disabled: boolean
    onPlace: (x: number, y: number) => void
}

export default function OmokBoard({ size, placements, disabled, onPlace }: OmokBoardProps) {
    const stoneAt = new Map(placements.map((p) => [`${p.x},${p.y}`, p.color]))
    // 225칸 위에서 상대가 방금 어디에 뒀는지는 표시가 없으면 사실상 찾을 수 없다.
    // placements 는 둔 순서대로이므로 마지막 항목이 직전 착수다.
    const lastMove = placements.length > 0 ? placements[placements.length - 1] : null

    return (
        <div className="aspect-square w-full max-w-[min(100%,80vh)]">
            <div
                className="grid h-full w-full rounded-md border bg-amber-50 p-2 dark:bg-amber-950/30"
                style={{
                    gridTemplateColumns: `repeat(${size}, minmax(0, 1fr))`,
                    // 행도 함께 고정한다. auto 로 두면 빈 칸의 높이가 내용에 맞춰져
                    // 판이 컨테이너 높이를 채우지 못하는 브라우저가 생긴다.
                    gridTemplateRows: `repeat(${size}, minmax(0, 1fr))`,
                }}
            >
                {Array.from({ length: size * size }, (_, index) => {
                    const x = index % size
                    const y = Math.floor(index / size)
                    const stone = stoneAt.get(`${x},${y}`)
                    const isLastMove = lastMove !== null && lastMove.x === x && lastMove.y === y

                    return (
                        <button
                            key={index}
                            type="button"
                            disabled={disabled || stone !== undefined}
                            onClick={() => onPlace(x, y)}
                            aria-label={`${x + 1}, ${y + 1}`}
                            className="relative flex items-center justify-center border border-amber-900/20 disabled:cursor-default"
                        >
                            {stone ? (
                                <span
                                    className={
                                        stone === "BLACK"
                                            ? "block h-[85%] w-[85%] rounded-full bg-neutral-900"
                                            : "block h-[85%] w-[85%] rounded-full border border-neutral-400 bg-white"
                                    }
                                />
                            ) : null}
                            {isLastMove ? (
                                <span
                                    aria-hidden
                                    className="pointer-events-none absolute inset-[15%] rounded-full ring-2 ring-rose-500"
                                />
                            ) : null}
                        </button>
                    )
                })}
            </div>
        </div>
    )
}

"use client"

import { PixelRows, STONE_PALETTES, STONE_ROWS } from "@/components/game/game-art"
import type { OmokPlacement, OmokStone } from "@/lib/omok-play"

// 판 위의 값 타입은 lib/omok-play.ts 에 있다(리듀서가 같은 타입으로 상태를 만든다).
// 여기서 다시 내보내는 것은 화면 쪽에서 <OmokBoard /> 만 import 하고도 쓸 수 있게 하려는 것뿐이다.
export type { OmokPlacement, OmokStone } from "@/lib/omok-play"

interface OmokBoardProps {
    size: number
    placements: OmokPlacement[]
    disabled: boolean
    onPlace: (x: number, y: number) => void
}

// 15×15 표준 화점(별). 다른 크기의 판에는 그리지 않는다 — 화점 규약이 판 크기마다 달라서
// 어설프게 일반화하느니 없는 편이 낫다.
const STAR_POINTS_15: [number, number][] = [
    [3, 3],
    [11, 3],
    [7, 7],
    [3, 11],
    [11, 11],
]

// 게임 허브 카드(game-art.tsx 의 OmokPixelArt)와 같은 레트로 팔레트. 어두운 매트 위에
// 나무 프레임과 판 — 테마 변수를 섞으면 라이트 모드에서 도트 대비가 무너지므로 고정한다.
const MAT = "#1a1c2c"
const FRAME = "#7a4a2e"
const WOOD = "#c98a54"
const LINE = "#8a5a3a"

/** 허브 카드와 같은 5×5 도트 돌. 칸 크기와 무관하게 crispEdges 가 도트를 지킨다. */
function PixelStone({ tone }: { tone: OmokStone }) {
    return (
        <svg
            aria-hidden
            viewBox="0 0 5 5"
            shapeRendering="crispEdges"
            className="block h-[92%] w-[92%]"
        >
            <PixelRows x={0} y={0} rows={STONE_ROWS} palette={STONE_PALETTES[tone === "BLACK" ? "black" : "white"]} />
        </svg>
    )
}

export default function OmokBoard({ size, placements, disabled, onPlace }: OmokBoardProps) {
    const stoneAt = new Map(placements.map((p) => [`${p.x},${p.y}`, p.color]))
    // 225칸 위에서 상대가 방금 어디에 뒀는지는 표시가 없으면 사실상 찾을 수 없다.
    // placements 는 둔 순서대로이므로 마지막 항목이 직전 착수다.
    const lastMove = placements.length > 0 ? placements[placements.length - 1] : null

    // 돌은 칸이 아니라 선의 교차점 위에 놓인다. 격자선을 각 칸의 "중심"을 지나게 그리면,
    // 칸 중앙에 놓이는 돌이 정확히 교차점 위에 올라간다 — 버튼 격자는 그대로 두고 선만
    // 배경 SVG 로 옮기는 것이 요점이다. viewBox 한 칸 = 격자 한 칸이므로 선 좌표는 i + 0.5.
    const lineOffsets = Array.from({ length: size }, (_, i) => i + 0.5)
    const starPoints = size === 15 ? STAR_POINTS_15 : []

    return (
        <div className="aspect-square w-full max-w-[min(100%,80vh)]">
            <div className="h-full w-full rounded-md p-2" style={{ background: MAT }}>
                <div className="h-full w-full p-1.5" style={{ background: FRAME }}>
                    {/* 패딩 없는 래퍼 하나가 SVG(선)와 버튼 격자에 정확히 같은 박스를 준다.
                        SVG 를 패딩 있는 컨테이너에 absolute 로 띄우면 width:100% 가 패딩 박스
                        기준으로 잡혀 격자보다 크게 늘어나고, 선과 돌이 반 칸씩 어긋난다. */}
                    <div className="relative h-full w-full" style={{ background: WOOD }}>
                        <svg
                            aria-hidden
                            className="pointer-events-none absolute inset-0 h-full w-full"
                            viewBox={`0 0 ${size} ${size}`}
                            preserveAspectRatio="none"
                        >
                            {lineOffsets.map((offset) => (
                                <g key={offset} stroke={LINE} strokeWidth={0.07}>
                                    <line x1={offset} y1={0.5} x2={offset} y2={size - 0.5} />
                                    <line x1={0.5} y1={offset} x2={size - 0.5} y2={offset} />
                                </g>
                            ))}
                            {starPoints.map(([x, y]) => (
                                <circle key={`${x}-${y}`} cx={x + 0.5} cy={y + 0.5} r={0.14} fill={LINE} />
                            ))}
                        </svg>
                        <div
                            className="relative grid h-full w-full"
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
                                        className="relative flex items-center justify-center disabled:cursor-default"
                                    >
                                        {stone ? <PixelStone tone={stone} /> : null}
                                        {isLastMove ? (
                                            <span
                                                aria-hidden
                                                className="pointer-events-none absolute inset-[6%] rounded-sm ring-2 ring-[#ffd166]"
                                            />
                                        ) : null}
                                    </button>
                                )
                            })}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    )
}

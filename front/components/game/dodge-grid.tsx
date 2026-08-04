"use client"

import { DODGE_PLAYER_ROWS, PixelRows } from "@/components/game/game-art"
import { DODGE_RULES } from "@/lib/dodge-engine"
import { DODGE_PLAYER_COLOR_COUNT, describeStackBadge, type DodgeGridPlayer } from "@/lib/dodge-play"

// 격자 위 말의 타입은 lib/dodge-play.ts 에 있다(리듀서가 같은 타입으로 상태를 만든다).
// 여기서 다시 내보내는 것은 화면 쪽에서 <DodgeGrid /> 만 import 하고도 쓸 수 있게 하려는 것뿐이다.
export type { DodgeGridPlayer } from "@/lib/dodge-play"

/**
 * 여덟 명을 구분하는 색. 격자 밖의 명단(로스터·기보 뷰어)이 배지에 쓴다.
 *
 * <p>이 배열이 components/ 에 있는 이유는 Tailwind 때문이다 — tailwind.config.ts 의 content
 * 글롭은 app/·components/ 만 훑고 <b>lib/ 는 훑지 않는다</b>. 이 문자열들을 lib/dodge-play.ts
 * 로 옮기면 클래스가 생성되지 않아 배지가 전부 투명해진다(로컬 dev 에서는 다른 파일에 같은
 * 클래스가 남아 있는 동안 우연히 보이다가 프로덕션 빌드에서 사라지는, 최악의 형태로).
 * lib 쪽은 색 <i>인덱스</i>만 계산한다.
 *
 * <p>붉은 계열은 비워 뒀다 — 장애물이 붉은 계열이라 말과 헷갈리면 안 된다.
 * 색만으로 여덟을 구분하는 것은 어차피 무리라(색각 이상 포함) 말 위에 번호도 함께 그린다.
 * 그 번호는 색과 달리 <b>접히지 않는다</b>(playerNumberOf) — 아홉 번째 배정부터 색은 1번과
 * 같아지지만 번호는 9로 남는다.
 */
export const DODGE_PLAYER_COLORS = [
    "bg-sky-500",
    "bg-amber-500",
    "bg-emerald-500",
    "bg-violet-500",
    "bg-fuchsia-500",
    "bg-cyan-500",
    "bg-lime-500",
    "bg-indigo-400",
] as const

/**
 * 격자 위 도트 캐릭터(SVG)용 팔레트. 위 Tailwind 배지 색과 같은 값이어야 명단의 배지 색으로
 * 판 위의 내 캐릭터를 찾을 수 있다 — body 는 각 Tailwind 클래스의 실제 hex, shade 는 그 700
 * 단계다. SVG fill 은 Tailwind 클래스를 못 쓰므로 hex 를 따로 둔다.
 */
const DODGE_PLAYER_PIXEL_COLORS = [
    { body: "#0ea5e9", shade: "#0369a1" }, // sky
    { body: "#f59e0b", shade: "#b45309" }, // amber
    { body: "#10b981", shade: "#047857" }, // emerald
    { body: "#8b5cf6", shade: "#6d28d9" }, // violet
    { body: "#d946ef", shade: "#a21caf" }, // fuchsia
    { body: "#06b6d4", shade: "#0e7490" }, // cyan
    { body: "#84cc16", shade: "#4d7c0f" }, // lime
    { body: "#818cf8", shade: "#4f46e5" }, // indigo
] as const

// 팔레트 길이와 lib 쪽 나머지 연산의 계수가 어긋나면 두 사람이 같은 색·다른 번호를 갖는다.
// 타입 단계에서 고정한다 — 색을 하나 지우면 여기서 컴파일이 깨진다.
const _paletteLengthMatchesColorCount: typeof DODGE_PLAYER_COLOR_COUNT = DODGE_PLAYER_COLORS.length
void _paletteLengthMatchesColorCount
const _pixelPaletteMatchesColorCount: typeof DODGE_PLAYER_COLOR_COUNT = DODGE_PLAYER_PIXEL_COLORS.length
void _pixelPaletteMatchesColorCount

// 게임 허브 카드(game-art.tsx 의 DodgePixelArt)와 같은 레트로 팔레트.
const FIELD = "#1a1c2c"
const STAR = "#3b4368"
const GROUND_LINE = "#454b73"
const GROUND = "#20243a"

/**
 * 낙하 블록의 색. 열(x) 기준으로 골라 같은 블록이 떨어지는 동안 색이 유지된다.
 * 전부 붉은 계열로 유지한다 — 말 팔레트(위)가 붉은 계열을 비워 둔 것과 짝이다.
 * 주황도 amber 말과 헷갈리지 않게 붉은 쪽으로 당겨 뒀다.
 */
const OBSTACLE_COLORS: { top: string; bottom: string }[] = [
    { top: "#ff5d5d", bottom: "#c23a4a" },
    { top: "#ff8a4d", bottom: "#c95a2e" },
    { top: "#ff77a8", bottom: "#c74f7f" },
]

/** 장식용 별. 위치는 퍼센트 좌표로 고정 — 매 렌더 같은 하늘이어야 한다. */
const STARS: [number, number][] = [
    [9, 6],
    [31, 34],
    [68, 12],
    [90, 22],
    [22, 58],
    [83, 64],
    [47, 44],
    [60, 78],
]

interface DodgeGridProps {
    players: DodgeGridPlayer[]
    obstacles: { x: number; y: number }[]
    /**
     * 접근성 이름. 여기서 만들지 않고 받는다 — 첫 프레임 전에는 인원을 세면 안 되는데
     * (describeGridLabel), 그 판단은 화면 상태를 아는 lib 쪽에 있고 테스트로 고정돼 있다.
     */
    label: string
}

export default function DodgeGrid({ players, obstacles, label }: DodgeGridProps) {
    const obstacleAt = new Set(obstacles.map((o) => `${o.x},${o.y}`))

    // 한 칸에 여러 명이 설 수 있다 — 서버의 DodgeGame 은 말끼리의 충돌을 보지 않는다.
    // Map<key, player> 로 만들면 마지막 한 명만 남아 다른 사람이 판에서 사라진 것처럼 보인다.
    const playersAt = new Map<string, DodgeGridPlayer[]>()
    for (const player of players) {
        const key = `${player.x},${player.y}`
        const stack = playersAt.get(key)
        if (stack) {
            stack.push(player)
        } else {
            playersAt.set(key, [player])
        }
    }

    return (
        <div
            role="img"
            aria-label={label}
            className="w-full max-w-[min(100%,60vh)]"
            style={{ aspectRatio: `${DODGE_RULES.cols} / ${DODGE_RULES.rows}` }}
        >
            <div
                className="relative h-full w-full overflow-hidden rounded-md border border-[#2e3350]"
                style={{ background: FIELD }}
            >
                {/* 별 하늘. 장식이므로 판정과 무관하고, 말·블록 뒤에 깔린다. */}
                {STARS.map(([left, top]) => (
                    <span
                        key={`${left}-${top}`}
                        aria-hidden
                        className="absolute h-[3px] w-[3px]"
                        style={{ left: `${left}%`, top: `${top}%`, background: STAR }}
                    />
                ))}

                {/* 바닥. 마지막 행의 말이 그 위에 서 있는 것처럼 보이도록 살짝 겹친다. */}
                <span aria-hidden className="absolute inset-x-0 bottom-0 h-[2.5%]" style={{ background: GROUND }} />
                <span
                    aria-hidden
                    className="absolute inset-x-0 bottom-[2.5%] h-[2px]"
                    style={{ background: GROUND_LINE }}
                />

                <div
                    className="relative grid h-full w-full"
                    style={{
                        gridTemplateColumns: `repeat(${DODGE_RULES.cols}, minmax(0, 1fr))`,
                        // 행도 함께 고정한다. auto 로 두면 빈 칸의 높이가 내용에 맞춰져
                        // 격자가 컨테이너 높이를 채우지 못한다(omok-board 와 같은 이유).
                        gridTemplateRows: `repeat(${DODGE_RULES.rows}, minmax(0, 1fr))`,
                    }}
                >
                    {Array.from({ length: DODGE_RULES.cols * DODGE_RULES.rows }, (_, index) => {
                        const x = index % DODGE_RULES.cols
                        const y = Math.floor(index / DODGE_RULES.cols)
                        const key = `${x},${y}`
                        const stack = playersAt.get(key)
                        // 같은 칸에 내가 있으면 반드시 내 말을 그린다 — 내 말이 남의 말에 가려
                        // 사라지면 어디에 있는지 모른 채 조작하게 된다.
                        const shown = stack?.find((player) => player.isSelf) ?? stack?.[0]
                        // 가려진 말들. 번호를 직접 그린다 — title 은 터치 기기에서 보이지 않는다.
                        const stackBadge = describeStackBadge(
                            (stack ?? [])
                                .filter((player) => player !== shown)
                                .map((player) => player.playerNumber)
                        )
                        const obstacle = obstacleAt.has(key)
                            ? OBSTACLE_COLORS[x % OBSTACLE_COLORS.length]
                            : null
                        const piece = shown
                            ? DODGE_PLAYER_PIXEL_COLORS[shown.colorIndex % DODGE_PLAYER_PIXEL_COLORS.length]
                            : null

                        return (
                            <div key={index} className="relative">
                                {obstacle ? (
                                    <span aria-hidden className="absolute inset-[10%]" style={{ background: obstacle.bottom }}>
                                        <span
                                            className="absolute inset-x-0 top-0 h-[72%]"
                                            style={{ background: obstacle.top }}
                                        />
                                    </span>
                                ) : null}
                                {shown && piece ? (
                                    <span
                                        title={stack?.map((player) => player.displayName).join(", ")}
                                        className={[
                                            "absolute inset-[4%] flex items-end justify-center",
                                            shown.isSelf ? "rounded-sm ring-2 ring-white/80" : "",
                                        ].join(" ")}
                                    >
                                        <svg
                                            aria-hidden
                                            viewBox="0 0 7 6"
                                            shapeRendering="crispEdges"
                                            preserveAspectRatio="xMidYMax meet"
                                            className="h-full w-full"
                                        >
                                            <PixelRows
                                                x={0}
                                                y={0}
                                                rows={DODGE_PLAYER_ROWS}
                                                palette={{ b: piece.body, e: "#12242e", d: piece.shade }}
                                            />
                                        </svg>
                                        <span
                                            className="absolute inset-x-0 top-[34%] text-center text-[0.55rem] font-bold leading-none text-white"
                                            style={{ textShadow: "0 1px 2px rgba(0,0,0,.7)" }}
                                        >
                                            {shown.playerNumber > 0 ? shown.playerNumber : "?"}
                                        </span>
                                    </span>
                                ) : null}
                                {stackBadge ? (
                                    <span
                                        title={stack?.map((player) => player.displayName).join(", ")}
                                        className="absolute -right-0.5 -top-0.5 rounded-sm bg-[#ffd166] px-0.5 text-[0.5rem] font-bold leading-none text-[#1a1c2c]"
                                    >
                                        {stackBadge}
                                    </span>
                                ) : null}
                            </div>
                        )
                    })}
                </div>
            </div>
        </div>
    )
}

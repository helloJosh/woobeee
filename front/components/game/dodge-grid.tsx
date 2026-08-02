"use client"

import { DODGE_RULES } from "@/lib/dodge-engine"
import { DODGE_PLAYER_COLOR_COUNT, describeStackBadge, type DodgeGridPlayer } from "@/lib/dodge-play"

// 격자 위 말의 타입은 lib/dodge-play.ts 에 있다(리듀서가 같은 타입으로 상태를 만든다).
// 여기서 다시 내보내는 것은 화면 쪽에서 <DodgeGrid /> 만 import 하고도 쓸 수 있게 하려는 것뿐이다.
export type { DodgeGridPlayer } from "@/lib/dodge-play"

/**
 * 여덟 명을 구분하는 색.
 *
 * <p>이 배열이 components/ 에 있는 이유는 Tailwind 때문이다 — tailwind.config.ts 의 content
 * 글롭은 app/·components/ 만 훑고 <b>lib/ 는 훑지 않는다</b>. 이 문자열들을 lib/dodge-play.ts
 * 로 옮기면 클래스가 생성되지 않아 말이 전부 투명해진다(로컬 dev 에서는 다른 파일에 같은
 * 클래스가 남아 있는 동안 우연히 보이다가 프로덕션 빌드에서 사라지는, 최악의 형태로).
 * lib 쪽은 색 <i>인덱스</i>만 계산한다.
 *
 * <p>붉은 계열은 비워 뒀다 — 장애물이 destructive(빨강)이라 말과 헷갈리면 안 된다.
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

// 팔레트 길이와 lib 쪽 나머지 연산의 계수가 어긋나면 두 사람이 같은 색·다른 번호를 갖는다.
// 타입 단계에서 고정한다 — 색을 하나 지우면 여기서 컴파일이 깨진다.
const _paletteLengthMatchesColorCount: typeof DODGE_PLAYER_COLOR_COUNT = DODGE_PLAYER_COLORS.length
void _paletteLengthMatchesColorCount

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
                className="grid h-full w-full gap-px rounded-md border bg-border p-px"
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

                    return (
                        <div key={index} className="relative bg-background">
                            {obstacleAt.has(key) ? (
                                <span aria-hidden className="absolute inset-[8%] rounded-sm bg-destructive" />
                            ) : null}
                            {shown ? (
                                <span
                                    title={stack?.map((player) => player.displayName).join(", ")}
                                    className={[
                                        "absolute inset-[8%] flex items-center justify-center rounded-full",
                                        "text-[0.55rem] font-bold leading-none text-white",
                                        DODGE_PLAYER_COLORS[shown.colorIndex % DODGE_PLAYER_COLORS.length],
                                        shown.isSelf ? "ring-2 ring-foreground" : "",
                                    ].join(" ")}
                                >
                                    {shown.playerNumber > 0 ? shown.playerNumber : "?"}
                                </span>
                            ) : null}
                            {stackBadge ? (
                                <span
                                    title={stack?.map((player) => player.displayName).join(", ")}
                                    className="absolute -right-0.5 -top-0.5 rounded-sm bg-foreground px-0.5 text-[0.5rem] font-bold leading-none text-background"
                                >
                                    {stackBadge}
                                </span>
                            ) : null}
                        </div>
                    )
                })}
            </div>
        </div>
    )
}

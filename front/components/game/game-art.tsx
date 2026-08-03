import type { ReactNode } from "react"

/**
 * 게임 허브 카드 상단의 픽셀아트 일러스트. 외부 이미지 파일 없이 SVG <rect> 로만 그린다 —
 * 에셋 파이프라인이 없어도 되고, 어떤 배율에서도 `crispEdges` 가 도트를 뭉개지 않게 지킨다.
 * 판단 로직이 전혀 없는 순수 표시 컴포넌트라 lib/ 가 아니라 여기 둔다.
 *
 * 두 일러스트 모두 어두운 아케이드 화면(#1a1c2c) 위에 그린다. 라이트/다크 테마의 카드 어느
 * 쪽에 올려도 "게임 스크린" 으로 읽히도록 팔레트를 자체 완결로 고정했다 — 테마 변수를 섞으면
 * 라이트 모드에서 도트 대비가 무너진다.
 */

type Palette = Record<string, string>

/** 문자 격자를 1×1 rect 로 푼다. `.` 또는 팔레트에 없는 문자는 투명. */
function PixelRows({ x, y, rows, palette }: { x: number; y: number; rows: string[]; palette: Palette }) {
    const cells: ReactNode[] = []
    rows.forEach((row, dy) => {
        Array.from(row).forEach((ch, dx) => {
            const fill = palette[ch]
            if (!fill) {
                return
            }
            cells.push(<rect key={`${dx}-${dy}`} x={x + dx} y={y + dy} width={1} height={1} fill={fill} />)
        })
    })
    return <g>{cells}</g>
}

const STONE_ROWS = [
    ".bbb.",
    "bsbbd",
    "bbbbd",
    "bbbbd",
    ".ddd.",
]

const STONE_PALETTES = {
    black: { b: "#2e2e38", s: "#5c5c74", d: "#15151b" },
    white: { b: "#f1f1f4", s: "#ffffff", d: "#c3c3d2" },
} as const

/** 교차점 (cx, cy) 에 5×5 픽셀 돌을 올린다. */
function PixelStone({ cx, cy, tone }: { cx: number; cy: number; tone: keyof typeof STONE_PALETTES }) {
    return <PixelRows x={cx - 2} y={cy - 2} rows={STONE_ROWS} palette={STONE_PALETTES[tone]} />
}

const OMOK_VERTICAL_LINES = [11, 17, 23, 29, 35, 41, 47, 53]
const OMOK_HORIZONTAL_LINES = [5, 11, 17, 23, 29, 35]

// 흑이 방금 대각 5목을 완성한 판. 흑 5수, 백 4수 — 흑이 이긴 직후의 수순이 맞아떨어지는 배치다.
const OMOK_BLACK_STONES: [number, number][] = [
    [17, 5],
    [23, 11],
    [29, 17],
    [35, 23],
    [41, 29],
]

const OMOK_WHITE_STONES: [number, number][] = [
    [23, 5],
    [29, 11],
    [35, 17],
    [47, 23],
]

export function OmokPixelArt() {
    return (
        <svg
            viewBox="0 0 64 40"
            className="block h-auto w-full"
            shapeRendering="crispEdges"
            role="img"
            aria-label="오목판 위에 흑이 대각선 오목을 완성한 픽셀아트"
        >
            <rect x={0} y={0} width={64} height={40} fill="#1a1c2c" />
            <rect x={7} y={1} width={50} height={38} fill="#7a4a2e" />
            <rect x={8} y={2} width={48} height={36} fill="#c98a54" />

            {OMOK_VERTICAL_LINES.map((x) => (
                <rect key={`v-${x}`} x={x} y={5} width={1} height={31} fill="#8a5a3a" />
            ))}
            {OMOK_HORIZONTAL_LINES.map((y) => (
                <rect key={`h-${y}`} x={11} y={y} width={43} height={1} fill="#8a5a3a" />
            ))}

            {OMOK_WHITE_STONES.map(([cx, cy]) => (
                <PixelStone key={`w-${cx}-${cy}`} cx={cx} cy={cy} tone="white" />
            ))}
            {OMOK_BLACK_STONES.map(([cx, cy]) => (
                <PixelStone key={`b-${cx}-${cy}`} cx={cx} cy={cy} tone="black" />
            ))}

            {/* 승리 반짝임 — 마지막 착수 주변 */}
            <rect x={44} y={26} width={1} height={1} fill="#ffd166" />
            <rect x={45} y={31} width={1} height={1} fill="#ffd166" />
            <rect x={38} y={32} width={1} height={1} fill="#ffd166" />
        </svg>
    )
}

const DODGE_PLAYER_ROWS = [
    ".bbbbb.",
    "bbebebb",
    "bbbbbbb",
    ".bbbbb.",
    ".b...b.",
    ".d...d.",
]

const DODGE_PLAYER_PALETTE: Palette = { b: "#39d7e0", e: "#12242e", d: "#1d8a94" }

const DODGE_STARS: [number, number][] = [
    [6, 2],
    [20, 14],
    [44, 18],
    [58, 8],
    [14, 22],
    [54, 26],
]

export function DodgePixelArt() {
    return (
        <svg
            viewBox="0 0 64 40"
            className="block h-auto w-full"
            shapeRendering="crispEdges"
            role="img"
            aria-label="떨어지는 블록들을 피하는 플레이어 픽셀아트"
        >
            <rect x={0} y={0} width={64} height={40} fill="#1a1c2c" />

            {DODGE_STARS.map(([x, y]) => (
                <rect key={`s-${x}-${y}`} x={x} y={y} width={1} height={1} fill="#3b4368" />
            ))}

            {/* 떨어지는 블록들 — 위쪽의 옅은 픽셀은 낙하 잔상 */}
            <rect x={12} y={1} width={1} height={2} fill="#7a3550" />
            <rect x={10} y={4} width={5} height={3} fill="#ff5d5d" />
            <rect x={10} y={7} width={5} height={1} fill="#c23a4a" />

            <rect x={31} y={6} width={1} height={1} fill="#7a4a35" />
            <rect x={32} y={8} width={1} height={1} fill="#7a4a35" />
            <rect x={30} y={10} width={4} height={3} fill="#ffa94d" />
            <rect x={30} y={13} width={4} height={1} fill="#cf7a2e" />

            <rect x={50} y={0} width={1} height={2} fill="#7a3550" />
            <rect x={48} y={3} width={6} height={2} fill="#ff77a8" />
            <rect x={48} y={5} width={6} height={1} fill="#c74f7f" />

            {/* 플레이어 바로 위까지 내려온 블록 */}
            <rect x={24} y={20} width={1} height={2} fill="#7a3550" />
            <rect x={22} y={24} width={4} height={3} fill="#ff5d5d" />
            <rect x={22} y={27} width={4} height={1} fill="#c23a4a" />

            {/* 오른쪽으로 피하는 플레이어와 이동 잔상 */}
            <rect x={26} y={31} width={1} height={1} fill="#9aa3c9" />
            <rect x={25} y={33} width={2} height={1} fill="#9aa3c9" />
            <PixelRows x={29} y={30} rows={DODGE_PLAYER_ROWS} palette={DODGE_PLAYER_PALETTE} />

            {/* 바닥 */}
            <rect x={0} y={36} width={64} height={1} fill="#454b73" />
            <rect x={0} y={37} width={64} height={3} fill="#20243a" />
        </svg>
    )
}

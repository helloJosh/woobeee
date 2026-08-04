/**
 * 서버(app-webflux)의 DodgeGame 을 그대로 옮긴 것이다.
 * 기보 재생은 이 포트가 서버와 한 글자도 다르지 않아야 성립한다 — 바꿀 때는 양쪽을 같이 바꾼다.
 *
 * v3 규칙: 옛 "이동 칸" 하나를 3×3 서브칸으로 쪼갰다. 격자는 36×48 서브칸, 플레이어는 3×3
 * 박스(좌표는 왼쪽 위), 입력 1회에 3서브칸 이동, 장애물은 가변 크기 박스이고 틱당 1서브칸씩
 * 떨어진다.
 */

export const DODGE_RULES = {
    cols: 36,
    rows: 48,
    playerSize: 3,
    moveStep: 3,
    spawnSlots: 12,
    subcellsPerCell: 3,
    tickMs: 100,
    baseSpawn: 0.01,
    spawnStep: 0.01,
    spawnStepTicks: 100,
    maxSpawn: 0.15,
    fallSpeedStepTicks: 300,
    maxFallSpeed: 3,
    minObstacleW: 2,
    maxObstacleW: 5,
    minObstacleH: 2,
    maxObstacleH: 3,
} as const

export interface Cell {
    x: number
    y: number
}

/** 낙하 블록. (x, y) 는 왼쪽 위 서브칸이고 w×h 서브칸을 차지한다. */
export interface Obstacle {
    x: number
    y: number
    w: number
    h: number
}

export type DirectionName = "UP" | "DOWN" | "LEFT" | "RIGHT"

const DELTAS: Record<DirectionName, Cell> = {
    UP: { x: 0, y: -1 },
    DOWN: { x: 0, y: 1 },
    LEFT: { x: -1, y: 0 },
    RIGHT: { x: 1, y: 0 },
}

/**
 * 서버의 {@code Xorshift32(int seed)} 와 같다. **잘라낸 뒤에 0 을 검사해야 한다** — 자바는
 * 생성자 인자가 이미 `int` 라 32비트로 잘린 값을 보고 0 을 판정하지만, JS 는 `number` 라
 * 우리가 직접 잘라야 같은 지점에서 같은 판정을 한다.
 *
 * <p>순서가 뒤바뀌어 있으면(`seed === 0 ? 1 : seed | 0`) "문자 그대로 0 은 아니지만 잘라내면
 * 0 이 되는" 값 — `undefined`, `null`, `"0"`, `2**32` — 이 그대로 state 0 으로 들어간다.
 * xorshift 는 0 에서 영원히 0 을 뱉으므로 `nextDouble()` 이 항상 0 이 되고, 그러면 매 틱 모든
 * 슬롯에서 장애물이 쏟아져 몇 틱 만에 전원이 죽는 "그럴듯한 짧은 게임" 이 예외 하나 없이 그려진다.
 */
export function xorshift32(seed: number) {
    const truncated = seed | 0
    let state = truncated === 0 ? 1 : truncated

    const nextInt = () => {
        state ^= state << 13
        state |= 0
        state ^= state >>> 17
        state ^= state << 5
        state |= 0
        return state >>> 0
    }

    return {
        nextInt,
        nextDouble: () => nextInt() / 4294967296,
    }
}

export function spawnProbability(tick: number): number {
    const raised = DODGE_RULES.baseSpawn + DODGE_RULES.spawnStep * Math.floor(tick / DODGE_RULES.spawnStepTicks)
    return Math.min(raised, DODGE_RULES.maxSpawn)
}

/** 서버 DodgeRules.fallSpeed 와 같다 — 30초(300틱)마다 1씩 올라 3에서 멈춘다. */
export function fallSpeed(tick: number): number {
    return Math.min(1 + Math.floor(tick / DODGE_RULES.fallSpeedStepTicks), DODGE_RULES.maxFallSpeed)
}

export function startingCells(playerCount: number): Cell[] {
    const y = DODGE_RULES.rows - DODGE_RULES.playerSize
    return Array.from({ length: playerCount }, (_, i) => {
        // 서버의 DodgeRules.startingCells 와 **같은 식**이어야 한다: 옛 12칸 공식
        //   round((i + 0.5) * spawnSlots / playerCount - 0.5) 를 [0, spawnSlots-1] 로 클램프한
        // 슬롯에 ×3. Java 의 Math.round 와 JS 의 Math.round 는 둘 다 .5 를 +∞ 쪽으로 올리므로
        // 그대로 옮겨진다. floor 기반 공식으로 쓰면 8인 게임의 배치가 서버와 **틱 1부터**
        // 갈린다 — 재생이 통째로 다른 게임이 된다.
        const slot = Math.round(((i + 0.5) * DODGE_RULES.spawnSlots) / playerCount - 0.5)
        const clamped = Math.min(Math.max(slot, 0), DODGE_RULES.spawnSlots - 1)
        return { x: clamped * DODGE_RULES.subcellsPerCell, y }
    })
}

export interface DodgeFrame {
    tick: number
    positions: Record<string, Cell>
    obstacles: Obstacle[]
    eliminatedThisTick: string[]
    finished: boolean
}

/** 서버 Obstacle.overlaps 와 같은 식 — 장애물 박스와 [left..right]×[top..bottom] 의 겹침. */
function overlapsBox(obstacle: Obstacle, left: number, top: number, right: number, bottom: number): boolean {
    return left <= obstacle.x + obstacle.w - 1 && obstacle.x <= right && top <= obstacle.y + obstacle.h - 1 && obstacle.y <= bottom
}

export function createDodgeGame(participantIds: string[], seed: number) {
    const random = xorshift32(seed)
    const positions = new Map<string, Cell>()
    const eliminationOrder: string[][] = []

    startingCells(participantIds.length).forEach((cell, index) => {
        positions.set(participantIds[index], cell)
    })

    let obstacles: Obstacle[] = []
    let tick = 0
    let finished = false
    let spawning = true

    const frame = (eliminated: string[]): DodgeFrame => ({
        tick,
        positions: Object.fromEntries(positions),
        obstacles: [...obstacles],
        eliminatedThisTick: eliminated,
        finished,
    })

    const advanceOneTick = (inputs: Record<string, DirectionName | undefined>): DodgeFrame => {
        if (finished) {
            return frame([])
        }

        const previousPositions = new Map(
            [...positions].map(([id, cell]) => [id, { x: cell.x, y: cell.y }])
        )

        for (const [participantId, name] of Object.entries(inputs)) {
            const current = positions.get(participantId)
            const delta = name ? DELTAS[name] : undefined
            if (!current || !delta) {
                continue
            }
            const x = current.x + delta.x * DODGE_RULES.moveStep
            const y = current.y + delta.y * DODGE_RULES.moveStep
            if (
                x < 0 ||
                x > DODGE_RULES.cols - DODGE_RULES.playerSize ||
                y < 0 ||
                y > DODGE_RULES.rows - DODGE_RULES.playerSize
            ) {
                continue
            }
            positions.set(participantId, { x, y })
        }

        const fall = fallSpeed(tick)
        const fallen: Obstacle[] = []
        for (const obstacle of obstacles) {
            const y = obstacle.y + fall
            if (y < DODGE_RULES.rows) {
                fallen.push({ x: obstacle.x, y, w: obstacle.w, h: obstacle.h })
            }
        }

        if (spawning) {
            const probability = spawnProbability(tick)
            // 슬롯 0 부터 오름차순. 굴림 순서와 굴림 수(히트당 폭 1회 + 높이 1회)가 난수열의
            // 일부다 — 하나만 달라져도 이후 스폰 전체가 서버와 갈린다.
            for (let slot = 0; slot < DODGE_RULES.spawnSlots; slot++) {
                if (random.nextDouble() < probability) {
                    const w =
                        DODGE_RULES.minObstacleW +
                        Math.floor(random.nextDouble() * (DODGE_RULES.maxObstacleW - DODGE_RULES.minObstacleW + 1))
                    const h =
                        DODGE_RULES.minObstacleH +
                        Math.floor(random.nextDouble() * (DODGE_RULES.maxObstacleH - DODGE_RULES.minObstacleH + 1))
                    const x = Math.min(slot * DODGE_RULES.subcellsPerCell, DODGE_RULES.cols - w)
                    fallen.push({ x, y: 0, w, h })
                }
            }
        }

        obstacles = fallen

        // 충돌은 두 갈래다: (1) 끝점 박스 겹침(AABB), (2) 상호 통과(스왑) — 낙하가 2서브칸
        // 이상인 구간에서는 위로 이동하는 플레이어와 블록이 겹침 없이 서로를 지나칠 수 있다.
        // 참가자의 현재 박스가 장애물의 직전 박스(이번 틱 낙하량만큼 위)와 겹치고 참가자의
        // 직전 박스가 장애물의 현재 박스와 겹치면 뚫고 지나간 것이다. 서버
        // DodgeGame.detectCollisions 와 같은 근거·같은 식이다.
        const eliminated: string[] = []
        for (const [participantId, now] of positions) {
            const before = previousPositions.get(participantId)
            const hit = obstacles.some((obstacle) => {
                if (
                    overlapsBox(
                        obstacle,
                        now.x,
                        now.y,
                        now.x + DODGE_RULES.playerSize - 1,
                        now.y + DODGE_RULES.playerSize - 1
                    )
                ) {
                    return true
                }
                if (!before) {
                    return false
                }
                const obstacleBefore = { x: obstacle.x, y: obstacle.y - fall, w: obstacle.w, h: obstacle.h }
                return (
                    overlapsBox(
                        obstacleBefore,
                        now.x,
                        now.y,
                        now.x + DODGE_RULES.playerSize - 1,
                        now.y + DODGE_RULES.playerSize - 1
                    ) &&
                    overlapsBox(
                        obstacle,
                        before.x,
                        before.y,
                        before.x + DODGE_RULES.playerSize - 1,
                        before.y + DODGE_RULES.playerSize - 1
                    )
                )
            })
            if (hit) {
                eliminated.push(participantId)
            }
        }

        eliminated.forEach((participantId) => positions.delete(participantId))
        if (eliminated.length > 0) {
            eliminationOrder.push([...eliminated])
        }

        tick += 1
        // 서버(DodgeGame)와 같은 조건이다. 아무도 안 남으면 무조건 끝이지만, "한 명 남음"이
        // 종료인 것은 원래 둘 이상으로 시작한 게임에서만이다 — 1인 게임은 시작부터 한 명이므로
        // 그 한 명이 맞을 때까지 계속된다. `positions.size <= 1` 만 보면 1인 기보가 틱 1에서
        // 끝나 버려 서버와 길이가 달라진다.
        if (positions.size === 0 || (participantIds.length > 1 && positions.size <= 1)) {
            finished = true
        }

        return frame(eliminated)
    }

    // 이탈. 입력 스트림 밖에서 상태를 바꾸는 사건이라 기보의 departures 가 이걸 재현한다.
    // 서버 DodgeGame.eliminate 와 한 줄씩 대응한다: 이미 끝났거나 그 참가자가 이미 없으면
    // 아무 일도 하지 않는 멱등 연산이고, 그렇지 않으면 그 틱의 탈락자 버킷을 하나 쌓는다.
    // 여기서는 `positions.size <= 1` 만 본다 — 서버와 같다. 1인 게임에서 그 한 명이 이탈하면
    // positions 가 비므로 이 조건으로도 끝난다.
    const eliminate = (participantId: string) => {
        if (finished || !positions.delete(participantId)) {
            return
        }
        eliminationOrder.push([participantId])
        if (positions.size <= 1) {
            finished = true
        }
    }

    const finalRanks = (): Record<string, number> => {
        const ranks: Record<string, number> = {}
        const alive = [...positions.keys()]
        let nextRank = 1

        if (alive.length > 0) {
            alive.forEach((id) => {
                ranks[id] = 1
            })
            nextRank = 1 + alive.length
        }

        for (let i = eliminationOrder.length - 1; i >= 0; i--) {
            const bucket = eliminationOrder[i]
            bucket.forEach((id) => {
                ranks[id] = nextRank
            })
            nextRank += bucket.length
        }

        participantIds.forEach((id) => {
            if (ranks[id] === undefined) {
                ranks[id] = participantIds.length
            }
        })
        return ranks
    }

    return {
        get tick() {
            return tick
        },
        get finished() {
            return finished
        },
        advanceOneTick,
        eliminate,
        finalRanks,
        disableSpawning: () => {
            spawning = false
        },
        forcePosition: (participantId: string, cell: Cell) => positions.set(participantId, cell),
        forceObstacles: (boxes: Obstacle[]) => {
            obstacles = [...boxes]
        },
    }
}

export interface DodgeReplayData {
    seed: number
    participantIds: string[]
    inputsByTick: Record<number, Record<string, DirectionName>>
    // 이탈은 입력이 아니라 상태를 직접 바꾸는 사건이라, 없으면 떠난 참가자가 재생에서
    // 계속 살아 피하고 있게 되어 승자와 길이가 원본과 달라진다.
    departuresByTick: Record<number, string[]>
}

/** 서버 DodgeReplayRunner.MAX_TICKS 와 같은 값. 손상된 기보가 무한 루프에 빠지는 것만 막는다. */
export const REPLAY_MAX_TICKS = 100_000

export type DodgeGameInstance = ReturnType<typeof createDodgeGame>

/**
 * 기보 재생의 **한 틱**. 재생 경로가 둘(테스트용 `rerunReplay`, 화면용 뷰어)이라 이 한 스텝을
 * 두 군데에 각각 적으면 반드시 갈라진다 — 실제로 v2 로 올릴 때 `rerunReplay` 에만 departures 를
 * 넣고 뷰어는 inputsByTick 만 도는 상태로 남았고, 그러면 정작 **사람이 보는 화면**이 v2 가 막으려던
 * 바로 그 틀린 게임을 그린다. 그래서 스텝은 여기 하나뿐이고 둘 다 이걸 부른다.
 *
 * <p>순서가 규칙이다: 그 틱의 이탈을 먼저 기록된 순서대로 반영하고, 그 이탈이 게임을 끝냈으면
 * `advanceOneTick` 은 건너뛴다 — 원본에서도 끝난 게임의 그 호출은 no-op 이었으므로 그래야 틱
 * 수가 정확히 같다. 그 경우 `null` 을 돌려준다(= "이 틱은 진행되지 않았다").
 */
export function stepReplay(game: DodgeGameInstance, replay: DodgeReplayData): DodgeFrame | null {
    const currentTick = game.tick

    for (const participantId of replay.departuresByTick[currentTick] ?? []) {
        game.eliminate(participantId)
    }
    if (game.finished) {
        return null
    }

    return game.advanceOneTick(replay.inputsByTick[currentTick] ?? {})
}

/**
 * @param maxTicks 테스트 전용 이음매. 서버의 `DodgeReplayRunner(int maxTicks)` 패키지 전용
 *   생성자와 같은 목적이다 — 상한에 실제로 도달하는 경로를 십만 틱을 돌리지 않고 검증하기
 *   위한 것이고, 그 경로(끝나지 않는 기보를 조용히 "짧은 정상 게임"으로 돌려주지 않는다)는
 *   이 이음매 없이는 실행으로 확인할 방법이 없다. 기본값은 프로덕션 상한 그대로다.
 */
export function rerunReplay(replay: DodgeReplayData, maxTicks: number = REPLAY_MAX_TICKS) {
    const game = createDodgeGame(replay.participantIds, replay.seed)

    while (!game.finished && game.tick < maxTicks) {
        if (stepReplay(game, replay) === null) {
            break
        }
    }

    // 서버 DodgeReplayRunner 와 같은 태도다. 조용히 안 끝난 게임을 돌려주면 finished 를
    // 확인하지 않은 호출자가 손상된/무한한 기보를 "짧은 정상 게임"으로 취급한다.
    if (!game.finished) {
        throw new Error(
            `Dodge replay for seed ${replay.seed} did not finish within ${maxTicks} ticks` +
                " — likely a malformed or non-terminating replay"
        )
    }

    return game
}

/**
 * 헤더가 싣고 있는 규칙 값들. 서버(DodgeReplayWriter)가 이걸 굳이 파일에 쓰는 이유는 클라이언트가
 * 자기 상수와 대조하라는 뜻이다 — 서버 상수가 바뀐 채 저장된 옛 기보를 새 상수로 재생하면 예외도
 * 경고도 없이 다른 게임이 그려진다. 그래서 `v` 와 같은 태도로 즉시 던진다.
 */
const HEADER_RULES: ReadonlyArray<[string, number]> = [
    ["cols", DODGE_RULES.cols],
    ["rows", DODGE_RULES.rows],
    ["playerSize", DODGE_RULES.playerSize],
    ["moveStep", DODGE_RULES.moveStep],
    ["spawnSlots", DODGE_RULES.spawnSlots],
    ["tickMs", DODGE_RULES.tickMs],
    ["baseSpawn", DODGE_RULES.baseSpawn],
    ["spawnStep", DODGE_RULES.spawnStep],
    ["spawnStepTicks", DODGE_RULES.spawnStepTicks],
    ["maxSpawn", DODGE_RULES.maxSpawn],
    ["fallSpeedStepTicks", DODGE_RULES.fallSpeedStepTicks],
    ["maxFallSpeed", DODGE_RULES.maxFallSpeed],
    ["minObstacleW", DODGE_RULES.minObstacleW],
    ["maxObstacleW", DODGE_RULES.maxObstacleW],
    ["minObstacleH", DODGE_RULES.minObstacleH],
    ["maxObstacleH", DODGE_RULES.maxObstacleH],
]

/** 서버가 seed 로 쓰는 자바 `int` 의 범위. 이 밖의 값은 이 기보를 쓴 서버가 만들 수 없다. */
const INT32_MIN = -2147483648
const INT32_MAX = 2147483647

/**
 * 헤더의 `seed`. 서버는 언제나 0 이 아닌 자바 `int` 를 쓴다
 * (`UuidGameIdGenerator.nextSeed`). 그 범위를 벗어나거나 정수가 아닌 값 — 필드가 통째로 없어
 * `undefined` 인 경우, `null`, 문자열 `"0"`, `2**32` — 은 이 기보를 쓴 서버가 만들 수 없는
 * 값이므로 재생하지 않는다.
 *
 * <p>재생을 거절하는 것이 요점이다. 이런 값들은 {@link xorshift32} 에 그대로 들어가면
 * 예외 없이 **다른 게임**을 그린다. `HEADER_RULES` 불일치와 같은 태도로 즉시 던진다.
 */
function requireSeed(value: unknown): number {
    if (typeof value !== "number" || !Number.isInteger(value) || value < INT32_MIN || value > INT32_MAX) {
        throw new Error(
            `Dodge replay header seed=${String(value)} is not a 32-bit integer;` +
                " the server can only write one, so refuse to replay it"
        )
    }
    return value
}

/**
 * 헤더의 `players`. 여기서 나오는 participantId 목록이 재생의 참가자 전원이고, 그 **수**가
 * 시작 칸을 정한다(`startingCells`) — 즉 이 목록이 틀리면 틱 1부터 다른 게임이 된다.
 *
 * <p>예전에는 `(header.players ?? []).map((p: any) => p.participantId)` 였다. 목록이 없으면
 * 참가자 0명으로 재생이 시작돼 첫 틱에 곧바로 "끝난" 두 프레임짜리 빈 격자가 나왔고,
 * `participantId` 가 없는 항목은 문자열 `"undefined"` 를 키로 삼아 조용히 한 자리를 차지했다.
 * 둘 다 오류 없이 그려진다 — 그래서 던진다.
 */
function requireParticipantIds(value: unknown): string[] {
    if (!Array.isArray(value) || value.length === 0) {
        throw new Error(
            "Dodge replay header players must be a non-empty array;" +
                " without it the reader would render an empty two-frame game instead of failing"
        )
    }
    return value.map((player, index) => {
        const participantId =
            typeof player === "object" && player !== null
                ? (player as { participantId?: unknown }).participantId
                : undefined
        if (typeof participantId !== "string" || participantId.length === 0) {
            throw new Error(
                `Dodge replay header players[${index}].participantId is missing or not a string;` +
                    " that player would be keyed by \"undefined\" and silently take a seat"
            )
        }
        return participantId
    })
}

/**
 * 중복 participantId 를 거른다. 이것도 "서버가 쓸 수 없는 값" 이다 —
 * {@code DodgeReplay.participantIds} 는 방의 멤버 목록에서 오고 participantId 는 방 안에서
 * 유일하다.
 *
 * <p>거르지 않으면 조용히 다른 게임이 된다. 중복이 둘 있으면 명단 길이는 2라
 * {@link startingCells} 가 <b>두 개</b>의 시작 칸을 만드는데, 위치 맵의 키는 하나뿐이라 두
 * 번째가 첫 번째를 덮어쓴다 — 첫 프레임부터 사람 수가 원본과 다르고, 종료 조건
 * (`participantIds.length > 1 && positions.size <= 1`)까지 갈린다. 예외도 경고도 없다.
 */
function requireDistinct(participantIds: string[]): string[] {
    if (new Set(participantIds).size !== participantIds.length) {
        throw new Error(
            "Dodge replay header players contains duplicate participantIds;" +
                " the seat count and the position map would disagree from the first frame"
        )
    }
    return participantIds
}

export function parseReplayNdjson(text: string): DodgeReplayData {
    const lines = text.trim().split("\n")
    const header = JSON.parse(lines[0])
    if (header.v !== 3) {
        throw new Error(
            `Unsupported dodge replay version ${header.v}; this reader needs v3` +
                " — v2 and older replays were recorded under different rules and cannot be replayed"
        )
    }
    if (header.prng !== "xorshift32") {
        throw new Error(`Unsupported dodge replay prng ${header.prng}; this reader implements xorshift32`)
    }
    for (const [field, expected] of HEADER_RULES) {
        if (header[field] !== expected) {
            throw new Error(
                `Dodge replay header ${field}=${header[field]} does not match this client's ${expected};` +
                    " the two engines would diverge, so refuse to replay it"
            )
        }
    }
    // 규칙 상수를 대조한 다음, 재생의 두 뼈대(난수 씨앗·참가자 명단)를 검사한다. 둘 다
    // 틀리면 예외 없이 다른 게임이 그려지는 값이므로 여기서 막는 것 말고는 방법이 없다.
    const seed = requireSeed(header.seed)
    const participantIds = requireDistinct(requireParticipantIds(header.players))

    const inputsByTick: Record<number, Record<string, DirectionName>> = {}
    const departuresByTick: Record<number, string[]> = {}

    for (let i = 1; i < lines.length; i++) {
        const line = JSON.parse(lines[i])
        if (line.moves) {
            inputsByTick[line.tick] = line.moves
        }
        if (line.departures) {
            departuresByTick[line.tick] = line.departures
        }
    }

    return {
        seed,
        participantIds,
        inputsByTick,
        departuresByTick,
    }
}

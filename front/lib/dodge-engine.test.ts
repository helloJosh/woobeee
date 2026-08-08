import { describe, expect, it } from "vitest"
import {
    DODGE_RULES,
    createDodgeGame,
    fallSpeed,
    REPLAY_MAX_TICKS,
    parseReplayNdjson,
    rerunReplay,
    spawnProbability,
    startingCells,
    stepReplay,
    xorshift32,
    type Cell,
    type DirectionName,
} from "./dodge-engine"

describe("xorshift32", () => {
    it("matches the server sequence for seed 1", () => {
        const random = xorshift32(1)

        expect(random.nextInt()).toBe(270369)
        expect(random.nextInt()).toBe(67634689)
        expect(random.nextInt()).toBe(2647435461)
    })

    it("produces values inside the unit interval", () => {
        const random = xorshift32(123456789)
        for (let i = 0; i < 1000; i++) {
            const value = random.nextDouble()
            expect(value).toBeGreaterThanOrEqual(0)
            expect(value).toBeLessThan(1)
        }
    })

    it("rewrites a zero seed to one", () => {
        expect(xorshift32(0).nextInt()).toBe(xorshift32(1).nextInt())
    })
})

describe("spawnProbability", () => {
    it("starts at 0.01 and steps every 100 ticks up to 0.15", () => {
        expect(spawnProbability(0)).toBeCloseTo(0.01)
        expect(spawnProbability(99)).toBeCloseTo(0.01)
        expect(spawnProbability(100)).toBeCloseTo(0.02)
        expect(spawnProbability(100000)).toBeCloseTo(0.15)
    })

    it("ramps the fall speed every 300 ticks up to 3", () => {
        expect(fallSpeed(0)).toBe(1)
        expect(fallSpeed(299)).toBe(1)
        expect(fallSpeed(300)).toBe(2)
        expect(fallSpeed(600)).toBe(3)
        expect(fallSpeed(100000)).toBe(3)
    })
})

describe("dodge grid", () => {
    it("uses the server dimensions", () => {
        expect(DODGE_RULES.cols).toBe(36)
        expect(DODGE_RULES.rows).toBe(48)
        expect(DODGE_RULES.playerSize).toBe(3)
        expect(DODGE_RULES.moveStep).toBe(3)
        expect(DODGE_RULES.spawnSlots).toBe(12)
        expect(DODGE_RULES.tickMs).toBe(100)
    })

    // 서버 DodgeRulesTest.startingCellsForEightPlayersAreExactlyTheseSubcells 와 같은 골든이다.
    // "서로 다르고 격자 안"만 확인하면 floor 기반의 틀린 공식도 통과한다 — 실제로 이 문서의
    // 첫 판이 그 공식이었고, 8인 게임이 틱 1부터 서버와 갈라졌다.
    it("places starting cells on exactly the server's subcells", () => {
        expect(startingCells(8).map((cell) => cell.x)).toEqual([0, 6, 9, 15, 18, 24, 27, 33])
        expect(startingCells(2).map((cell) => cell.x)).toEqual([9, 27])
        expect(startingCells(1).map((cell) => cell.x)).toEqual([18])
        expect(
            startingCells(8).every((cell) => cell.y === DODGE_RULES.rows - DODGE_RULES.playerSize)
        ).toBe(true)
    })
})

/**
 * 이동·충돌·종료의 규칙. 아래 골든은 입력 없이 도는 판만 재현하므로 **입력 경로 전체가 그
 * 바깥**이고, 실제로 리뷰의 변이 실험에서 `DELTAS.DOWN` 과 `DELTAS.RIGHT` 를 뒤집어도 모든
 * 테스트가 초록이었다. 서버와 한 칸이라도 다르게 움직이면 그 뒤 충돌 판정 전체가 갈라지는
 * 포트에서 그것은 이 파일이 존재하는 이유 자체가 비어 있었다는 뜻이다.
 */
describe("movement", () => {
    /** 스폰과 장애물이 없는 조용한 판. 이동만 남는다. */
    function quietGame(start: Cell) {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forceObstacles([])
        game.forcePosition("m:11", start)
        game.forcePosition("g:a", { x: 0, y: 0 })
        return game
    }

    it.each([
        ["UP", { x: 15, y: 21 }],
        ["DOWN", { x: 15, y: 27 }],
        ["LEFT", { x: 12, y: 24 }],
        ["RIGHT", { x: 18, y: 24 }],
    ] as Array<[DirectionName, Cell]>)("moves MOVE_STEP subcells %s", (direction, expected) => {
        const game = quietGame({ x: 15, y: 24 })

        expect(game.advanceOneTick({ "m:11": direction }).positions["m:11"]).toEqual(expected)
    })

    // 서버 DodgeGame.applyInputs 는 플레이어 박스(3×3)가 격자를 벗어나는 이동을 그 틱에서
    // 통째로 버린다(클램프가 아니라 무시다). 이 가드를 지우면 참가자가 판 밖으로 걸어 나간다.
    it.each([
        ["LEFT", { x: 0, y: 24 }],
        ["RIGHT", { x: DODGE_RULES.cols - DODGE_RULES.playerSize, y: 24 }],
        ["UP", { x: 15, y: 0 }],
        ["DOWN", { x: 15, y: DODGE_RULES.rows - DODGE_RULES.playerSize }],
    ] as Array<[DirectionName, Cell]>)("refuses to step off the grid going %s", (direction, edge) => {
        const game = quietGame(edge)

        expect(game.advanceOneTick({ "m:11": direction }).positions["m:11"]).toEqual(edge)
    })

    it("ignores an input from someone who is no longer on the board", () => {
        const game = createDodgeGame(["m:11", "g:a", "g:b"], 1)
        game.disableSpawning()
        game.forceObstacles([])
        game.eliminate("g:b")

        const frame = game.advanceOneTick({ "g:b": "UP", "m:11": "UP" })

        expect(game.finished).toBe(false)
        expect(frame.positions["g:b"]).toBeUndefined()
        // 3인의 첫 시작 칸은 x=6, 바닥(y=45). UP 한 번에 3서브칸 올라간다.
        expect(frame.positions["m:11"]).toEqual({
            x: 6,
            y: DODGE_RULES.rows - DODGE_RULES.playerSize - DODGE_RULES.moveStep,
        })
    })
})

describe("obstacles", () => {
    // 바닥 행에 닿은 장애물은 사라진다. 이 컬링을 지우면 격자 밖으로 계속 내려가며 영원히
    // 쌓인다 — 아래 골든의 3틱 창으로는 구조적으로 볼 수 없다(장애물이 바닥까지 47틱 걸린다).
    it("drops an obstacle whose top row falls past the bottom", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 0, y: 0 })
        game.forcePosition("g:a", { x: 33, y: 0 })
        game.forceObstacles([{ x: 15, y: DODGE_RULES.rows - 1, w: 2, h: 2 }])

        expect(game.advanceOneTick({}).obstacles).toEqual([])
    })

    it("moves an obstacle down exactly one subcell keeping its size", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 0, y: 0 })
        game.forcePosition("g:a", { x: 33, y: 0 })
        game.forceObstacles([{ x: 15, y: 3, w: 4, h: 2 }])

        expect(game.advanceOneTick({}).obstacles).toEqual([{ x: 15, y: 4, w: 4, h: 2 }])
    })
})

/**
 * 1인 게임의 종료 조건. 서버 DodgeGame 은 `positions.isEmpty() || (참가자 2명 이상 && 남은
 * 1명 이하)` 다. 이것을 `positions.size <= 1` 하나로 줄이면 1인 기보가 틱 1에서 끝나 버려
 * 서버와 길이가 달라지는데, 1인 게임을 만드는 테스트가 하나도 없었다.
 */
describe("a solo game", () => {
    it("does not end merely because one player is the only player", () => {
        const game = createDodgeGame(["solo"], 42)
        game.disableSpawning()
        game.forceObstacles([])

        game.advanceOneTick({})

        expect(game.tick).toBe(1)
        expect(game.finished).toBe(false)
    })

    it("ends when that one player is hit", () => {
        const game = createDodgeGame(["solo"], 42)
        game.disableSpawning()
        game.forcePosition("solo", { x: 15, y: 15 })
        game.forceObstacles([{ x: 15, y: 13, w: 2, h: 2 }])

        const frame = game.advanceOneTick({})

        expect(frame.eliminatedThisTick).toEqual(["solo"])
        expect(game.finished).toBe(true)
        expect(game.finalRanks()).toEqual({ solo: 1 })
    })
})

/**
 * `eliminate` 는 기보의 departures 가 지나가는 길이다. 멱등하지 않으면 같은 이탈이 두 번
 * 기록된 기보(또는 재접속 유예와 겹친 중복 이탈)가 유령 순위 버킷을 하나 더 쌓아 순위가
 * 통째로 밀린다.
 */
describe("eliminate", () => {
    it("is idempotent for a participant who already left", () => {
        const game = createDodgeGame(["a", "b", "c"], 42)

        game.eliminate("c")
        game.eliminate("c")
        game.eliminate("nobody")
        game.eliminate("b")

        expect(game.finished).toBe(true)
        expect(game.finalRanks()).toEqual({ a: 1, b: 2, c: 3 })
    })

    it("does nothing once the game is over", () => {
        const game = createDodgeGame(["a", "b"], 42)

        game.eliminate("b")
        game.eliminate("a")

        expect(game.finalRanks()).toEqual({ a: 1, b: 2 })
    })
})

/**
 * 끝난 판에서 `advanceOneTick` 은 아무 일도 하지 않는다. 이 no-op 은 기보 재생의 틱 수가
 * 원본과 정확히 같기 위한 조건이라 `stepReplay` 의 docblock 이 이미 근거로 삼고 있다.
 */
describe("a finished game", () => {
    it("does not advance, spawn or eliminate any further", () => {
        const game = createDodgeGame(["a", "b"], 42)
        game.eliminate("b")

        const before = game.tick
        const frame = game.advanceOneTick({ a: "LEFT" })

        expect(game.tick).toBe(before)
        expect(frame.tick).toBe(before)
        expect(frame.eliminatedThisTick).toEqual([])
        expect(frame.finished).toBe(true)
    })
})

describe("replay", () => {
    it("is deterministic for the same seed and inputs", () => {
        const players = ["m:11", "g:a", "g:b"]
        const replay = {
            seed: 987654321,
            participantIds: players,
            inputsByTick: { 3: { "m:11": "LEFT" } } as Record<number, Record<string, DirectionName>>,
            departuresByTick: {},
        }

        const first = rerunReplay(replay)
        const second = rerunReplay(replay)

        expect(first.finalRanks()).toEqual(second.finalRanks())
        expect(first.tick).toBe(second.tick)
    })

    it("terminates", () => {
        const game = rerunReplay({
            seed: 42,
            participantIds: ["m:11", "g:a"],
            inputsByTick: {},
            departuresByTick: {},
        })
        expect(game.finished).toBe(true)
    })

    // 이탈은 입력이 아니므로 departures 를 무시하면 떠난 참가자가 계속 살아 피하고 있게 되어
    // 승자와 길이가 달라진다. 아무 틱도 지나기 전에 둘이 떠나므로 tick 은 0 이고 남은 한 명이
    // 1위다 — 서버 DodgeReplayTest 의 같은 시나리오와 일치한다.
    it("applies departures before the tick they happened on", () => {
        const game = rerunReplay({
            seed: 12345,
            participantIds: ["m:11", "g:a", "g:c"],
            inputsByTick: {},
            departuresByTick: { 0: ["g:a", "g:c"] },
        })

        expect(game.tick).toBe(0)
        expect(game.finished).toBe(true)
        expect(game.finalRanks()).toEqual({ "m:11": 1, "g:c": 2, "g:a": 3 })
    })

    // 끝나지 않은 판을 조용히 돌려주면, finished 를 확인하지 않은 호출자가 손상된 기보를
    // "짧은 정상 게임" 으로 그린다 — 서버 DodgeReplayRunner 가 던지는 것과 같은 이유다.
    // 상한을 낮춰 부르는 것은 서버의 패키지 전용 DodgeReplayRunner(maxTicks) 와 같은 이음매다.
    it("refuses to return a game that never finished", () => {
        const replay = {
            seed: 42,
            participantIds: ["m:11", "g:a"],
            inputsByTick: {},
            departuresByTick: {},
        }

        // 이 판은 78틱짜리다(아래 골든). 3틱에서 잘리면 아직 끝나지 않았다.
        expect(() => rerunReplay(replay, 3)).toThrow(/did not finish within 3 ticks/)
        // 상한을 주지 않으면 프로덕션 상한이고, 같은 판이 정상적으로 끝난다.
        expect(rerunReplay(replay).finished).toBe(true)
    })

    it("uses the server's safety limit by default", () => {
        expect(REPLAY_MAX_TICKS).toBe(100_000)
    })
})

/**
 * `stepReplay` 는 재생 틱이 구현된 **유일한** 곳이라는 것이 계약이다 — 뷰어와 `rerunReplay` 가
 * 같은 함수를 부르지 않으면 재생의 어긋남이 화면에서 재현된다. 그 계약을 실행 가능하게 만드는
 * 것은 두 가지다: 이탈이 먼저 반영된다는 것과, 그 이탈이 게임을 끝냈으면 그 틱은 진행되지
 * 않았다는 뜻으로 `null` 이 온다는 것.
 */
describe("stepReplay", () => {
    it("reports a tick that never ran when a departure ended the game", () => {
        const replay = {
            seed: 12345,
            participantIds: ["m:11", "g:a"],
            inputsByTick: {},
            departuresByTick: { 0: ["g:a"] },
        }
        const game = createDodgeGame(replay.participantIds, replay.seed)

        expect(stepReplay(game, replay)).toBeNull()
        expect(game.tick).toBe(0)
        expect(game.finished).toBe(true)
    })

    it("advances the game and returns the frame when nobody left", () => {
        const replay = {
            seed: 12345,
            participantIds: ["m:11", "g:a"],
            inputsByTick: { 0: { "m:11": "LEFT" } } as Record<number, Record<string, DirectionName>>,
            departuresByTick: {},
        }
        const game = createDodgeGame(replay.participantIds, replay.seed)

        const frame = stepReplay(game, replay)

        expect(frame).not.toBeNull()
        expect(frame?.tick).toBe(1)
        // 2인의 시작 칸은 [9, 27]. LEFT 한 번이면 6 이다.
        expect(frame?.positions["m:11"]).toEqual({
            x: 6,
            y: DODGE_RULES.rows - DODGE_RULES.playerSize,
        })
    })
})

/**
 * 크로스 언어 골든. 위의 개별 테스트들은 이 포트의 각 조각을 고정하지만, "서버와 같은 판이
 * 나온다" 는 것 자체는 고정하지 못한다 — 조각이 전부 맞아도 조립이 어긋나면(입력 반영과 하강의
 * 순서, 충돌 판정 시점, 종료 조건, 크기 굴림 수) 재생은 다른 게임이 된다.
 *
 * <p>아래 문자열은 실제 서버 코드에서 뽑았다. `app-webflux/target/classes` 에 대고 jshell 로
 * `new DodgeGame(ids, seed)` 를 입력 없이 끝까지 돌려 틱 수 · 시작 칸 · 최종 순위(공동 순위
 * 포함) · 처음 세 틱의 장애물 목록(크기 포함)을 찍은 것을 그대로 옮긴 것이고, 열두 조합 전부
 * 이 포트와 바이트 단위로 같았다. 서버를 고쳤는데 여기를 안 고치면 이 테스트가 먼저 깨진다 —
 * 그게 목적이다.
 *
 * <p><b>재현은 한 줄이다</b> — 이 문자열들을 손으로 맞춰 보게 두면 안 된다:
 *
 * <pre>
 * jshell --class-path app-webflux/target/classes -q scripts/dodge-parity-trace.jsh
 * </pre>
 *
 * <p>1인 판이 목록에 있는 것은 장식이 아니다. 서버의 종료 조건은 "아무도 없거나, <b>둘 이상으로
 * 시작한 판</b>에서 한 명 남음" 이라 1인 판은 그 한 명이 맞을 때까지 계속된다 — 조건을
 * `positions.size <= 1` 하나로 줄이면 아래 세 줄이 전부 `ticks=1` 로 무너진다.
 */
describe("parity with the server DodgeGame", () => {
    const GOLDEN = [
        "seed=42 n=1 | ticks=165 | starts=p0=18,45 | ranks={p0=1} | obs=t1:(0,0,4,2)|t2:(0,1,4,2)|t3:(0,2,4,2)",
        "seed=42 n=2 | ticks=78 | starts=p0=9,45 p1=27,45 | ranks={p0=2, p1=1} | obs=t1:(0,0,4,2)|t2:(0,1,4,2)|t3:(0,2,4,2)",
        "seed=42 n=5 | ticks=103 | starts=p0=3,45 p1=9,45 p2=18,45 p3=24,45 p4=30,45 | ranks={p0=5, p1=4, p2=1, p3=3, p4=2} | obs=t1:(0,0,4,2)|t2:(0,1,4,2)|t3:(0,2,4,2)",
        "seed=42 n=8 | ticks=157 | starts=p0=0,45 p1=6,45 p2=9,45 p3=15,45 p4=18,45 p5=24,45 p6=27,45 p7=33,45 | ranks={p0=8, p1=2, p2=6, p3=4, p4=1, p5=5, p6=3, p7=7} | obs=t1:(0,0,4,2)|t2:(0,1,4,2)|t3:(0,2,4,2)",
        "seed=12345 n=1 | ticks=152 | starts=p0=18,45 | ranks={p0=1} | obs=t1:|t2:|t3:",
        "seed=12345 n=2 | ticks=81 | starts=p0=9,45 p1=27,45 | ranks={p0=1, p1=2} | obs=t1:|t2:|t3:",
        "seed=12345 n=5 | ticks=152 | starts=p0=3,45 p1=9,45 p2=18,45 p3=24,45 p4=30,45 | ranks={p0=1, p1=4, p2=2, p3=5, p4=3} | obs=t1:|t2:|t3:",
        "seed=12345 n=8 | ticks=153 | starts=p0=0,45 p1=6,45 p2=9,45 p3=15,45 p4=18,45 p5=24,45 p6=27,45 p7=33,45 | ranks={p0=1, p1=5, p2=5, p3=3, p4=3, p5=8, p6=7, p7=2} | obs=t1:|t2:|t3:",
        "seed=987654321 n=1 | ticks=162 | starts=p0=18,45 | ranks={p0=1} | obs=t1:|t2:|t3:",
        "seed=987654321 n=2 | ticks=127 | starts=p0=9,45 p1=27,45 | ranks={p0=1, p1=2} | obs=t1:|t2:|t3:",
        "seed=987654321 n=5 | ticks=162 | starts=p0=3,45 p1=9,45 p2=18,45 p3=24,45 p4=30,45 | ranks={p0=4, p1=1, p2=2, p3=3, p4=5} | obs=t1:|t2:|t3:",
        "seed=987654321 n=8 | ticks=162 | starts=p0=0,45 p1=6,45 p2=9,45 p3=15,45 p4=18,45 p5=24,45 p6=27,45 p7=33,45 | ranks={p0=6, p1=3, p2=1, p3=7, p4=2, p5=5, p6=4, p7=8} | obs=t1:|t2:|t3:",
    ]

    function traceOf(seed: number, playerCount: number): string {
        const ids = Array.from({ length: playerCount }, (_, i) => `p${i}`)
        const game = createDodgeGame(ids, seed)
        const starts = startingCells(playerCount)
            .map((cell, i) => `${ids[i]}=${cell.x},${cell.y}`)
            .join(" ")

        const obstacleTrace: string[] = []
        while (!game.finished && game.tick < 100000) {
            const frame = game.advanceOneTick({})
            if (frame.tick <= 3) {
                obstacleTrace.push(
                    `t${frame.tick}:` +
                        frame.obstacles.map((o) => `(${o.x},${o.y},${o.w},${o.h})`).join("")
                )
            }
        }

        const ranks = game.finalRanks()
        const sorted = Object.keys(ranks)
            .sort()
            .map((id) => `${id}=${ranks[id]}`)
            .join(", ")

        return `seed=${seed} n=${playerCount} | ticks=${game.tick} | starts=${starts}`
            + ` | ranks={${sorted}} | obs=${obstacleTrace.join("|")}`
    }

    // 갱신 방법: jshell --class-path app-webflux/target/classes -q scripts/dodge-parity-trace.jsh
    it("reproduces the server's trace for three seeds by four player counts", () => {
        const traces: string[] = []
        for (const seed of [42, 12345, 987654321]) {
            for (const playerCount of [1, 2, 5, 8]) {
                traces.push(traceOf(seed, playerCount))
            }
        }

        expect(traces).toEqual(GOLDEN)
    })
})

describe("parseReplayNdjson", () => {
    const header = {
        v: 3,
        gameType: "DODGE",
        cols: 36,
        rows: 48,
        playerSize: 3,
        moveStep: 3,
        spawnSlots: 12,
        tickMs: 100,
        seed: 7,
        prng: "xorshift32",
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
        players: [{ participantId: "m:11", displayName: "host" }],
    }

    it("reads moves and departures off the same tick line", () => {
        const text =
            JSON.stringify(header) +
            "\n" +
            JSON.stringify({ tick: 2, moves: { "m:11": "LEFT" }, departures: ["g:a"] }) +
            "\n"

        const replay = parseReplayNdjson(text)

        expect(replay.seed).toBe(7)
        expect(replay.participantIds).toEqual(["m:11"])
        expect(replay.inputsByTick[2]).toEqual({ "m:11": "LEFT" })
        expect(replay.departuresByTick[2]).toEqual(["g:a"])
    })

    // 헤더가 규칙을 싣는 이유는 클라이언트가 대조하라는 것이다. 말없이 자기 상수로 재생하면
    // 예외도 경고도 없이 다른 게임을 그린다 — v 가 다를 때와 같은 태도로 던져야 한다.
    it("refuses a header whose rules it cannot reproduce", () => {
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, cols: 12 }) + "\n")).toThrow(/cols/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, maxSpawn: 0.9 }) + "\n")).toThrow(/maxSpawn/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, moveStep: 1 }) + "\n")).toThrow(/moveStep/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, maxFallSpeed: 9 }) + "\n")).toThrow(
            /maxFallSpeed/
        )
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, maxObstacleW: 9 }) + "\n")).toThrow(
            /maxObstacleW/
        )
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, prng: "mulberry32" }) + "\n")).toThrow(/prng/)
        // v2 이하는 다른 규칙으로 기록된 기보다 — 조용히 다른 게임을 그리지 말고 거절한다.
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, v: 2 }) + "\n")).toThrow(/v2|version/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, v: 1 }) + "\n")).toThrow(/v1|version/)
    })

    /**
     * seed 는 재생 전체를 결정하는 값이고, 틀렸을 때의 실패 모양이 가장 나쁘다: 잘라내면 0 이
     * 되는 값은 xorshift 를 영구 0 으로 만들어 매 틱 슬롯 열두 개가 전부 쏟아지는 "짧고 그럴듯한
     * 게임" 을 예외 없이 그린다. 서버는 0 이 아닌 자바 int 만 쓰므로(UuidGameIdGenerator.nextSeed)
     * 그 밖의 값은 전부 손상이다.
     *
     * <p>`JSON.stringify` 는 `undefined` 필드를 아예 빼 버리므로, "필드가 없는" 경우가 곧
     * `seed: undefined` 케이스다.
     */
    it("refuses a seed the server could not have written", () => {
        const withSeed = (seed: unknown) =>
            JSON.stringify({ ...header, seed }) + "\n"

        expect(() => parseReplayNdjson(withSeed(undefined))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed(null))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed("0"))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed("7"))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed(1.5))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed(2 ** 32))).toThrow(/seed/)
        expect(() => parseReplayNdjson(withSeed(Number.NaN))).toThrow(/seed/)

        // 경계는 통과해야 한다 — 자바 int 의 양 끝과 음수 씨앗은 정상적인 값이다.
        expect(parseReplayNdjson(withSeed(-2147483648)).seed).toBe(-2147483648)
        expect(parseReplayNdjson(withSeed(2147483647)).seed).toBe(2147483647)
        expect(parseReplayNdjson(withSeed(-1)).seed).toBe(-1)
    })

    /**
     * players 는 참가자 명단이자 **인원수**다. 인원수가 시작 칸을 정하므로(startingCells)
     * 이것이 틀리면 틱 1부터 다른 게임이다. 빈 명단은 첫 틱에 곧바로 끝나는 두 프레임짜리
     * 빈 격자를, participantId 가 없는 항목은 `"undefined"` 라는 이름의 유령 참가자를 만든다.
     */
    it("refuses a players list it cannot key positions by", () => {
        const withPlayers = (players: unknown) =>
            JSON.stringify({ ...header, players }) + "\n"

        expect(() => parseReplayNdjson(withPlayers(undefined))).toThrow(/players/)
        expect(() => parseReplayNdjson(withPlayers(null))).toThrow(/players/)
        expect(() => parseReplayNdjson(withPlayers([]))).toThrow(/players/)
        expect(() => parseReplayNdjson(withPlayers({ "m:11": "host" }))).toThrow(/players/)
        expect(() => parseReplayNdjson(withPlayers([{ displayName: "host" }]))).toThrow(/participantId/)
        expect(() => parseReplayNdjson(withPlayers([{ participantId: 11 }]))).toThrow(/participantId/)
        expect(() => parseReplayNdjson(withPlayers([{ participantId: "" }]))).toThrow(/participantId/)
        expect(() => parseReplayNdjson(withPlayers(["m:11"]))).toThrow(/participantId/)
        // 두 번째 항목이 깨진 경우도 잡아야 한다 — 첫 항목만 보면 통과한다.
        expect(() =>
            parseReplayNdjson(withPlayers([{ participantId: "m:11" }, { displayName: "손님" }]))
        ).toThrow(/players\[1\]/)

        expect(
            parseReplayNdjson(withPlayers([{ participantId: "m:11" }, { participantId: "g:a" }]))
                .participantIds
        ).toEqual(["m:11", "g:a"])
    })

    /**
     * 같은 부류의 "서버가 쓸 수 없는 값" 이다 — participantId 는 방 안에서 유일하다. 중복이
     * 통과하면 명단 길이는 2인데 위치 맵의 키는 하나라, 시작 칸 두 개 중 하나가 즉시 다른
     * 하나를 덮어쓴다: 첫 프레임부터 사람 수가 원본과 다르고 종료 조건까지 갈린다.
     */
    it("refuses duplicate participantIds", () => {
        const withPlayers = (players: unknown) =>
            JSON.stringify({ ...header, players }) + "\n"

        expect(() =>
            parseReplayNdjson(withPlayers([{ participantId: "dup" }, { participantId: "dup" }]))
        ).toThrow(/duplicate/)
        expect(() =>
            parseReplayNdjson(
                withPlayers([
                    { participantId: "m:11" },
                    { participantId: "g:a" },
                    { participantId: "m:11" },
                ])
            )
        ).toThrow(/duplicate/)
    })

    /**
     * 중복이 통과했을 때의 관찰 가능한 증상. 이 테스트가 없으면 위 테스트는 "그냥 던진다"
     * 이상을 말하지 않는다 — 왜 위험한지가 여기 있다.
     */
    it("shows why a duplicate matters: the seat count and the position map disagree", () => {
        const twoSeats = createDodgeGame(["dup", "dup"], 7)
        const frame = twoSeats.advanceOneTick({})

        expect(startingCells(2)).toHaveLength(2)
        expect(Object.keys(frame.positions).length).toBeLessThan(2)
    })
})

/**
 * xorshift32 의 0 판정은 **잘라낸 뒤**에 해야 한다. 자바 생성자는 인자가 이미 int 라 그
 * 지점에서 판정하는데, JS 에서 순서를 뒤집으면 잘라서 0 이 되는 값들이 state 0 으로 들어가고
 * 수열이 영구히 0 이 된다 — 그러면 spawnProbability 와의 비교가 언제나 참이라 매 틱 모든
 * 슬롯에 장애물이 생긴다.
 */
describe("xorshift32 zero handling", () => {
    const firstThree = (seed: number) => {
        const random = xorshift32(seed)
        return [random.nextInt(), random.nextInt(), random.nextInt()]
    }

    it("treats a value that truncates to zero exactly like the literal zero seed", () => {
        const zero = firstThree(0)

        expect(zero).not.toEqual([0, 0, 0])
        expect(firstThree(2 ** 32)).toEqual(zero)
        expect(firstThree(-(2 ** 32))).toEqual(zero)
        expect(firstThree(undefined as unknown as number)).toEqual(zero)
        expect(firstThree(null as unknown as number)).toEqual(zero)
        expect(firstThree("0" as unknown as number)).toEqual(zero)
    })

    /**
     * 무너진 상태의 관찰 가능한 증상. `nextDouble()` 이 언제나 0 이면 어떤 spawnProbability
     * 와 비교해도 참이라 슬롯 열두 개가 매 틱 전부 생긴다.
     */
    it("never produces the all-zero stream that would spawn every slot", () => {
        for (const seed of [0, 2 ** 32, undefined as unknown as number]) {
            const random = xorshift32(seed)
            const draws = Array.from({ length: 12 }, () => random.nextDouble())
            expect(draws.some((value) => value >= DODGE_RULES.maxSpawn)).toBe(true)
        }
    })
})

describe("collision", () => {
    // v2 의 스왑 검사가 하던 일을 v3 에서는 끝점 겹침이 대신한다(이동량 = 플레이어 한 변이라
    // 겹침 없는 통과가 기하학적으로 불가능하다 — 서버 detectCollisions 주석 참조). 사용자
    // 행동은 같다: 위로 이동해 낙하 블록을 통과하려는 시도는 여전히 죽는다.
    it("treats moving up through a falling obstacle as a hit", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 15, y: 30 })
        game.forcePosition("g:a", { x: 0, y: 45 })
        game.forceObstacles([{ x: 15, y: 26, w: 2, h: 2 }])

        const frame = game.advanceOneTick({ "m:11": "UP" })

        expect(frame.eliminatedThisTick).toEqual(["m:11"])
    })

    // 낙하 2서브칸 구간의 상호 통과(스왑). 끝점 검사만 남기면 여기서 깨진다 — 서버
    // DodgeGameTest.mutualPassThroughAtDoubleFallSpeedIsACollision 과 같은 시나리오다.
    it("treats a mutual pass-through at double fall speed as a hit", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 15, y: 30 })
        game.forcePosition("g:a", { x: 0, y: 45 })
        // 낙하 속도가 2가 되는 300틱까지 조용히 돌린다.
        for (let i = 0; i < 300; i++) {
            game.advanceOneTick({})
        }
        game.forceObstacles([{ x: 15, y: 28, w: 2, h: 2 }])

        // m:11 은 30→27(위로 3), 블록은 28..29→30..31(아래로 2) — 끝점에서는 겹치지 않는다.
        const frame = game.advanceOneTick({ "m:11": "UP" })

        expect(frame.eliminatedThisTick).toEqual(["m:11"])
    })

    // 부분 겹침도 충돌이다 — 박스 판정이 "같은 칸" 판정으로 퇴화하면 여기서 깨진다.
    it("treats a one-subcell corner overlap as a hit", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 15, y: 30 })
        game.forcePosition("g:a", { x: 0, y: 45 })
        // 낙하 후 (17,29,2,2): 오른쪽 아래 서브칸 하나(17,30)만 플레이어 박스(15..17,30..32)와 겹친다.
        game.forceObstacles([{ x: 17, y: 28, w: 2, h: 2 }])

        const frame = game.advanceOneTick({})

        expect(frame.eliminatedThisTick).toEqual(["m:11"])
    })
})

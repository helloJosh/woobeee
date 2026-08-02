import { describe, expect, it } from "vitest"
import {
    DODGE_RULES,
    createDodgeGame,
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
    it("starts at 0.15 and steps every 100 ticks up to 0.6", () => {
        expect(spawnProbability(0)).toBeCloseTo(0.15)
        expect(spawnProbability(99)).toBeCloseTo(0.15)
        expect(spawnProbability(100)).toBeCloseTo(0.2)
        expect(spawnProbability(100000)).toBeCloseTo(0.6)
    })
})

describe("dodge grid", () => {
    it("uses the server dimensions", () => {
        expect(DODGE_RULES.cols).toBe(12)
        expect(DODGE_RULES.rows).toBe(16)
        expect(DODGE_RULES.tickMs).toBe(100)
    })

    // 서버 DodgeRulesTest.startingCellsForEightPlayersAreExactlyTheseColumns 와 같은 골든이다.
    // "서로 다르고 격자 안"만 확인하면 floor 기반의 틀린 공식도 통과한다 — 실제로 이 문서의
    // 첫 판이 그 공식이었고, 8인 게임이 틱 1부터 서버와 갈라졌다.
    it("places starting cells on exactly the server's columns", () => {
        expect(startingCells(8).map((cell) => cell.x)).toEqual([0, 2, 3, 5, 6, 8, 9, 11])
        expect(startingCells(2).map((cell) => cell.x)).toEqual([3, 9])
        expect(startingCells(1).map((cell) => cell.x)).toEqual([6])
        expect(startingCells(8).every((cell) => cell.y === DODGE_RULES.rows - 1)).toBe(true)
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
        ["UP", { x: 5, y: 7 }],
        ["DOWN", { x: 5, y: 9 }],
        ["LEFT", { x: 4, y: 8 }],
        ["RIGHT", { x: 6, y: 8 }],
    ] as Array<[DirectionName, Cell]>)("moves one cell %s", (direction, expected) => {
        const game = quietGame({ x: 5, y: 8 })

        expect(game.advanceOneTick({ "m:11": direction }).positions["m:11"]).toEqual(expected)
    })

    // 서버 DodgeGame.applyInputs 는 격자를 벗어나는 이동을 그 틱에서 통째로 버린다(클램프가
    // 아니라 무시다). 이 가드를 지우면 참가자가 판 밖으로 걸어 나간다.
    it.each([
        ["LEFT", { x: 0, y: 8 }],
        ["RIGHT", { x: DODGE_RULES.cols - 1, y: 8 }],
        ["UP", { x: 5, y: 0 }],
        ["DOWN", { x: 5, y: DODGE_RULES.rows - 1 }],
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
        expect(frame.positions["m:11"]).toEqual({ x: 2, y: DODGE_RULES.rows - 2 })
    })
})

describe("obstacles", () => {
    // 바닥 행에 닿은 장애물은 사라진다. 이 컬링을 지우면 격자 밖으로 계속 내려가며 영원히
    // 쌓인다 — 아래 골든의 3틱 창으로는 구조적으로 볼 수 없다(장애물이 바닥까지 16틱 걸린다).
    it("drops an obstacle that falls past the bottom row", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 0, y: 0 })
        game.forcePosition("g:a", { x: 11, y: 0 })
        game.forceObstacles([{ x: 5, y: DODGE_RULES.rows - 1 }])

        expect(game.advanceOneTick({}).obstacles).toEqual([])
    })

    it("moves an obstacle down exactly one row", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 0, y: 0 })
        game.forcePosition("g:a", { x: 11, y: 0 })
        game.forceObstacles([{ x: 5, y: 3 }])

        expect(game.advanceOneTick({}).obstacles).toEqual([{ x: 5, y: 4 }])
    })
})

/**
 * 1인 게임의 종료 조건. 서버 DodgeGame:130 은 `positions.isEmpty() || (참가자 2명 이상 && 남은
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
        game.forcePosition("solo", { x: 5, y: 5 })
        game.forceObstacles([{ x: 5, y: 4 }])

        const frame = game.advanceOneTick({})

        expect(frame.eliminatedThisTick).toEqual(["solo"])
        expect(game.finished).toBe(true)
        expect(game.finalRanks()).toEqual({ solo: 1 })
    })
})

/**
 * `eliminate` 는 v2 기보의 departures 가 지나가는 길이다. 멱등하지 않으면 같은 이탈이 두 번
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

    // v2 의 존재 이유. 이탈은 입력이 아니므로 departures 를 무시하면 떠난 참가자가 계속 살아
    // 피하고 있게 되어 승자와 길이가 달라진다. 아무 틱도 지나기 전에 둘이 떠나므로 tick 은 0 이고
    // 남은 한 명이 1위다 — 서버 DodgeReplayTest 의 같은 시나리오와 일치한다.
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
    // "짧은 정상 게임" 으로 그린다 — 서버 DodgeReplayRunner:59 가 던지는 것과 같은 이유다.
    // 상한을 낮춰 부르는 것은 서버의 패키지 전용 DodgeReplayRunner(maxTicks) 와 같은 이음매다.
    it("refuses to return a game that never finished", () => {
        const replay = {
            seed: 42,
            participantIds: ["m:11", "g:a"],
            inputsByTick: {},
            departuresByTick: {},
        }

        // 이 판은 19틱짜리다(위 골든). 3틱에서 잘리면 아직 끝나지 않았다.
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
 * 같은 함수를 부르지 않으면 v2 가 막으려던 바로 그 어긋남이 화면에서 재현된다. 그 계약을
 * 실행 가능하게 만드는 것은 두 가지다: 이탈이 먼저 반영된다는 것과, 그 이탈이 게임을 끝냈으면
 * 그 틱은 진행되지 않았다는 뜻으로 `null` 이 온다는 것.
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
        // 3인이 아니라 2인이므로 시작 칸은 [3, 9]. LEFT 한 번이면 2 다.
        expect(frame?.positions["m:11"]).toEqual({ x: 2, y: DODGE_RULES.rows - 1 })
    })
})

/**
 * 크로스 언어 골든. 위의 개별 테스트들은 이 포트의 각 조각을 고정하지만, "서버와 같은 판이
 * 나온다" 는 것 자체는 고정하지 못한다 — 조각이 전부 맞아도 조립이 어긋나면(입력 반영과 하강의
 * 순서, 충돌 판정 시점, 종료 조건) 재생은 다른 게임이 된다.
 *
 * <p>아래 문자열은 실제 서버 코드에서 뽑았다. `app-webflux/target/classes` 에 대고 jshell 로
 * `new DodgeGame(ids, seed)` 를 입력 없이 끝까지 돌려 틱 수 · 시작 칸 · 최종 순위(공동 순위
 * 포함) · 처음 세 틱의 장애물 목록을 찍은 것을 그대로 옮긴 것이고, 열두 조합 전부 이 포트와
 * 바이트 단위로 같았다. 서버를 고쳤는데 여기를 안 고치면 이 테스트가 먼저 깨진다 — 그게 목적이다.
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
        "seed=42 n=1 | ticks=23 | starts=p0=6,15 | ranks={p0=1} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=42 n=2 | ticks=19 | starts=p0=3,15 p1=9,15 | ranks={p0=1, p1=2} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=42 n=5 | ticks=24 | starts=p0=1,15 p1=3,15 p2=6,15 p3=8,15 p4=10,15 | ranks={p0=5, p1=1, p2=3, p3=2, p4=4} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=42 n=8 | ticks=24 | starts=p0=0,15 p1=2,15 p2=3,15 p3=5,15 p4=6,15 p5=8,15 p6=9,15 p7=11,15 | ranks={p0=7, p1=7, p2=1, p3=6, p4=3, p5=2, p6=5, p7=3} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=12345 n=1 | ticks=17 | starts=p0=6,15 | ranks={p0=1} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=12345 n=2 | ticks=19 | starts=p0=3,15 p1=9,15 | ranks={p0=2, p1=1} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=12345 n=5 | ticks=19 | starts=p0=1,15 p1=3,15 p2=6,15 p3=8,15 p4=10,15 | ranks={p0=3, p1=2, p2=3, p3=3, p4=1} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=12345 n=8 | ticks=21 | starts=p0=0,15 p1=2,15 p2=3,15 p3=5,15 p4=6,15 p5=8,15 p6=9,15 p7=11,15 | ranks={p0=1, p1=3, p2=4, p3=5, p4=6, p5=6, p6=2, p7=8} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=987654321 n=1 | ticks=54 | starts=p0=6,15 | ranks={p0=1} | obs=t1:(0,0)(4,0)|t2:(0,1)(4,1)|t3:(0,2)(4,2)",
        "seed=987654321 n=2 | ticks=20 | starts=p0=3,15 p1=9,15 | ranks={p0=1, p1=2} | obs=t1:(0,0)(4,0)|t2:(0,1)(4,1)|t3:(0,2)(4,2)",
        "seed=987654321 n=5 | ticks=43 | starts=p0=1,15 p1=3,15 p2=6,15 p3=8,15 p4=10,15 | ranks={p0=3, p1=2, p2=1, p3=3, p4=3} | obs=t1:(0,0)(4,0)|t2:(0,1)(4,1)|t3:(0,2)(4,2)",
        "seed=987654321 n=8 | ticks=43 | starts=p0=0,15 p1=2,15 p2=3,15 p3=5,15 p4=6,15 p5=8,15 p6=9,15 p7=11,15 | ranks={p0=8, p1=4, p2=2, p3=5, p4=1, p5=6, p6=6, p7=3} | obs=t1:(0,0)(4,0)|t2:(0,1)(4,1)|t3:(0,2)(4,2)",
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
                    `t${frame.tick}:` + frame.obstacles.map((cell) => `(${cell.x},${cell.y})`).join("")
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
        v: 2,
        gameType: "DODGE",
        cols: 12,
        rows: 16,
        tickMs: 100,
        seed: 7,
        prng: "xorshift32",
        baseSpawn: 0.15,
        spawnStep: 0.05,
        spawnStepTicks: 100,
        maxSpawn: 0.6,
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
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, cols: 20 }) + "\n")).toThrow(/cols/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, maxSpawn: 0.9 }) + "\n")).toThrow(/maxSpawn/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, prng: "mulberry32" }) + "\n")).toThrow(/prng/)
        expect(() => parseReplayNdjson(JSON.stringify({ ...header, v: 1 }) + "\n")).toThrow(/v1|version/)
    })
})

describe("collision", () => {
    it("treats a swap as a hit", () => {
        const game = createDodgeGame(["m:11", "g:a"], 1)
        game.disableSpawning()
        game.forcePosition("m:11", { x: 5, y: 10 })
        game.forcePosition("g:a", { x: 0, y: 15 })
        game.forceObstacles([{ x: 5, y: 9 }])

        const frame = game.advanceOneTick({ "m:11": "UP" })

        expect(frame.eliminatedThisTick).toEqual(["m:11"])
    })
})

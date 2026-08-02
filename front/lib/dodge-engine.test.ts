import { describe, expect, it } from "vitest"
import {
    DODGE_RULES,
    createDodgeGame,
    parseReplayNdjson,
    rerunReplay,
    spawnProbability,
    startingCells,
    stepReplay,
    xorshift32,
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
 * 포함) · 처음 세 틱의 장애물 목록을 찍은 것을 그대로 옮긴 것이고, 아홉 조합 전부 이 포트와
 * 바이트 단위로 같았다. 서버를 고쳤는데 여기를 안 고치면 이 테스트가 먼저 깨진다 — 그게 목적이다.
 *
 * <p>재현:
 * `jshell --class-path app-webflux/target/classes` 로 같은 루프를 돌린다.
 */
describe("parity with the server DodgeGame", () => {
    const GOLDEN = [
        "seed=42 n=2 | ticks=19 | starts=p0=3,15 p1=9,15 | ranks={p0=1, p1=2} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=42 n=5 | ticks=24 | starts=p0=1,15 p1=3,15 p2=6,15 p3=8,15 p4=10,15 | ranks={p0=5, p1=1, p2=3, p3=2, p4=4} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=42 n=8 | ticks=24 | starts=p0=0,15 p1=2,15 p2=3,15 p3=5,15 p4=6,15 p5=8,15 p6=9,15 p7=11,15 | ranks={p0=7, p1=7, p2=1, p3=6, p4=3, p5=2, p6=5, p7=3} | obs=t1:(0,0)(2,0)|t2:(0,1)(2,1)(4,0)(5,0)|t3:(0,2)(2,2)(4,1)(5,1)(1,0)",
        "seed=12345 n=2 | ticks=19 | starts=p0=3,15 p1=9,15 | ranks={p0=2, p1=1} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=12345 n=5 | ticks=19 | starts=p0=1,15 p1=3,15 p2=6,15 p3=8,15 p4=10,15 | ranks={p0=3, p1=2, p2=3, p3=3, p4=1} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
        "seed=12345 n=8 | ticks=21 | starts=p0=0,15 p1=2,15 p2=3,15 p3=5,15 p4=6,15 p5=8,15 p6=9,15 p7=11,15 | ranks={p0=1, p1=3, p2=4, p3=5, p4=6, p5=6, p6=2, p7=8} | obs=t1:(11,0)|t2:(11,1)(1,0)(4,0)(6,0)(8,0)|t3:(11,2)(1,1)(4,1)(6,1)(8,1)(5,0)",
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

    it("reproduces the server's trace for three seeds by three player counts", () => {
        const traces: string[] = []
        for (const seed of [42, 12345, 987654321]) {
            for (const playerCount of [2, 5, 8]) {
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

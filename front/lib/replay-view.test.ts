import { describe, expect, it } from "vitest"
import {
    buildDodgeReplayView,
    buildReplayView,
    clampReplayIndex,
    describeGameTypeName,
    describeReplayFailure,
    describeReplayFrameEvent,
    describeReplayHttpError,
    describeReplayLabel,
    describeReplayPlayerName,
    describeReplayPosition,
    describeResultSubtitle,
    describeResultTitle,
    formatEndedAt,
    hasMoreResults,
    maxReplayIndex,
    parseOmokReplayNdjson,
    replayStepDelayMs,
    OMOK_REPLAY_STEP_MS,
} from "./replay-view"
import { DODGE_RULES } from "./dodge-engine"
import { NETWORK_ERROR_MESSAGE } from "./game-errors"
import type { GameResultSummary } from "./types"

/**
 * 마이페이지와 기보 뷰어의 판단 전부. 컴포넌트에는 그리는 코드만 남겨 두고 여기로 내린 것들이라,
 * 조용히 틀릴 수 있는 것은 원칙적으로 전부 이 파일이 잡아야 한다.
 *
 * <p>장애물피하기 쪽 기대값은 <b>추측이 아니다</b> — dodge-engine.test.ts 의 서버 대조 golden
 * ("seed=42 n=2 | ticks=19 | starts=p0=3,15 p1=9,15") 에서 그대로 가져왔다. 그 줄은 jshell 로
 * 서버 바이트코드를 돌려 뽑은 것이다.
 */

const OMOK_HEADER = {
    v: 1,
    gameType: "OMOK",
    boardSize: 15,
    players: [
        { participantId: "m:11", color: "BLACK", displayName: "흑돌이" },
        { participantId: "g:a", color: "WHITE", displayName: "백돌이" },
    ],
}

function omokNdjson(moves: object[], header: object = OMOK_HEADER): string {
    return [JSON.stringify(header), ...moves.map((move) => JSON.stringify(move))].join("\n") + "\n"
}

const OMOK_MOVES = [
    { t: 0, p: "m:11", x: 7, y: 7 },
    { t: 1, p: "g:a", x: 7, y: 8 },
    { t: 2, p: "m:11", x: 8, y: 7 },
]

const DODGE_HEADER = {
    v: 2,
    gameType: "DODGE",
    cols: 12,
    rows: 16,
    tickMs: 100,
    seed: 42,
    prng: "xorshift32",
    baseSpawn: 0.15,
    spawnStep: 0.05,
    spawnStepTicks: 100,
    maxSpawn: 0.6,
    players: [
        { participantId: "m:11", displayName: "회원" },
        { participantId: "g:a", displayName: "손님" },
    ],
}

function dodgeNdjson(lines: object[], header: object = DODGE_HEADER): string {
    return [JSON.stringify(header), ...lines.map((line) => JSON.stringify(line))].join("\n") + "\n"
}

describe("parseOmokReplayNdjson", () => {
    it("colours every move from the header, in the order they were played", () => {
        const replay = parseOmokReplayNdjson(omokNdjson(OMOK_MOVES))

        expect(replay.boardSize).toBe(15)
        expect(replay.placements).toEqual([
            { x: 7, y: 7, color: "BLACK" },
            { x: 7, y: 8, color: "WHITE" },
            { x: 8, y: 7, color: "BLACK" },
        ])
        expect(replay.players.map((player) => player.displayName)).toEqual(["흑돌이", "백돌이"])
    })

    // 색을 모르는 착수를 흑으로 떨어뜨리는 것은 "그럴듯한 기본값" 이지만, 그 순간 판 전체가
    // 원본과 다른 대국이 된다. 아무 신호 없이.
    it("refuses a move whose participant is not in the header", () => {
        expect(() => parseOmokReplayNdjson(omokNdjson([{ t: 0, p: "g:zzz", x: 1, y: 1 }]))).toThrow(
            /unknown participant/
        )
    })

    it("refuses a board size it cannot draw", () => {
        expect(() =>
            parseOmokReplayNdjson(omokNdjson(OMOK_MOVES, { ...OMOK_HEADER, boardSize: 19 }))
        ).toThrow(/boardSize/)
    })

    it("refuses a version or game type it does not understand", () => {
        expect(() => parseOmokReplayNdjson(omokNdjson([], { ...OMOK_HEADER, v: 2 }))).toThrow(/version/)
        expect(() => parseOmokReplayNdjson(omokNdjson([], { ...OMOK_HEADER, gameType: "DODGE" }))).toThrow(
            /Not an omok replay/
        )
    })

    it("refuses a header that does not describe exactly two distinct colours", () => {
        expect(() =>
            parseOmokReplayNdjson(omokNdjson([], { ...OMOK_HEADER, players: [OMOK_HEADER.players[0]] }))
        ).toThrow(/two players/)
        expect(() =>
            parseOmokReplayNdjson(
                omokNdjson([], {
                    ...OMOK_HEADER,
                    players: [OMOK_HEADER.players[0], { ...OMOK_HEADER.players[1], color: "BLACK" }],
                })
            )
        ).toThrow(/same stone colour/)
        expect(() =>
            parseOmokReplayNdjson(
                omokNdjson([], {
                    ...OMOK_HEADER,
                    players: [OMOK_HEADER.players[0], { ...OMOK_HEADER.players[1], color: "GREEN" }],
                })
            )
        ).toThrow(/colour/)
    })

    it("refuses a coordinate that is off the board", () => {
        expect(() => parseOmokReplayNdjson(omokNdjson([{ t: 0, p: "m:11", x: 15, y: 0 }]))).toThrow(
            /off the board/
        )
        expect(() => parseOmokReplayNdjson(omokNdjson([{ t: 0, p: "m:11", x: 0, y: -1 }]))).toThrow(
            /off the board/
        )
    })

    it("tolerates the trailing newline and blank lines the writer may leave", () => {
        const text = omokNdjson(OMOK_MOVES) + "\n\n"
        expect(parseOmokReplayNdjson(text).placements).toHaveLength(3)
    })

    it("refuses an empty file instead of replaying an empty board", () => {
        expect(() => parseOmokReplayNdjson("   \n")).toThrow(/empty/)
    })

    it("falls back to the participant id when a display name is missing", () => {
        const replay = parseOmokReplayNdjson(
            omokNdjson([], {
                ...OMOK_HEADER,
                players: [{ participantId: "m:11", color: "BLACK" }, OMOK_HEADER.players[1]],
            })
        )
        expect(replay.players[0].displayName).toBe("m:11")
    })
})

describe("buildDodgeReplayView", () => {
    it("opens on tick 0 — the starting cells, before any obstacle exists", () => {
        const view = buildDodgeReplayView(dodgeNdjson([]), null)

        // golden: starts=p0=3,15 p1=9,15
        expect(view.frames[0].tick).toBe(0)
        expect(view.frames[0].obstacles).toEqual([])
        expect(view.frames[0].players.map((player) => [player.x, player.y])).toEqual([
            [3, 15],
            [9, 15],
        ])
    })

    // golden: seed=42 n=2 | ticks=19. 프레임은 시작 판 하나 + 진행된 틱 열아홉이다.
    it("runs the same number of ticks the server did", () => {
        const view = buildDodgeReplayView(dodgeNdjson([]), null)

        expect(view.frames).toHaveLength(20)
        expect(view.frames[view.frames.length - 1].tick).toBe(19)
    })

    /**
     * 이 프로젝트에서 기보 v2 가 존재하는 이유 그 자체다. 이탈을 무시하면 떠난 사람이 계속
     * 살아서 피하고 있게 되고, 재생은 19틱짜리 <b>다른 게임</b>이 된다.
     */
    it("honours departures — a leaver ends the game exactly where the server ended it", () => {
        const view = buildDodgeReplayView(dodgeNdjson([{ tick: 3, departures: ["g:a"] }]), null)

        expect(view.frames).toHaveLength(4)
        expect(view.frames[view.frames.length - 1].tick).toBe(3)
    })

    it("replays the recorded inputs — a move changes where the piece stands", () => {
        const withInput = buildDodgeReplayView(dodgeNdjson([{ tick: 0, moves: { "m:11": "LEFT" } }]), null)
        const withoutInput = buildDodgeReplayView(dodgeNdjson([]), null)

        const moved = withInput.frames[1].players.find((player) => player.participantId === "m:11")
        const still = withoutInput.frames[1].players.find((player) => player.participantId === "m:11")

        expect(moved?.x).toBe(2)
        expect(still?.x).toBe(3)
    })

    it("names, numbers and colours every piece from the header order", () => {
        const view = buildDodgeReplayView(dodgeNdjson([]), "g:a")

        expect(view.roster).toEqual([
            {
                participantId: "m:11",
                displayName: "회원",
                colorIndex: 0,
                playerNumber: 1,
                isSelf: false,
            },
            {
                participantId: "g:a",
                displayName: "손님",
                colorIndex: 1,
                playerNumber: 2,
                isSelf: true,
            },
        ])
        expect(view.frames[0].players[1]).toMatchObject({
            displayName: "손님",
            playerNumber: 2,
            isSelf: true,
        })
    })

    it("reports who was hit on the tick it happened", () => {
        const view = buildDodgeReplayView(dodgeNdjson([]), null)
        const hits = view.frames.flatMap((frame) => frame.eliminatedThisTick)

        // 두 명짜리 판은 첫 탈락으로 끝난다 — 그 한 번이 마지막 프레임에 실려 있어야 한다.
        expect(hits).toHaveLength(1)
        expect(view.frames[view.frames.length - 1].eliminatedThisTick).toEqual(hits)
    })

    it("refuses a header whose rules this client cannot reproduce", () => {
        expect(() => buildDodgeReplayView(dodgeNdjson([], { ...DODGE_HEADER, v: 1 }), null)).toThrow()
        expect(() => buildDodgeReplayView(dodgeNdjson([], { ...DODGE_HEADER, cols: 20 }), null)).toThrow()
    })
})

describe("buildReplayView", () => {
    it("uses a different parser per game — the two formats are not interchangeable", () => {
        const omok = buildReplayView("OMOK", omokNdjson(OMOK_MOVES), "m:11")
        const dodge = buildReplayView("DODGE", dodgeNdjson([]), "m:11")

        expect(omok.gameType).toBe("OMOK")
        expect(dodge.gameType).toBe("DODGE")
        // 오목 기보를 장애물피하기로 열면(전적의 gameType 이 틀렸거나 URL 이 섞였을 때)
        // 조용히 빈 판을 그리지 않고 던진다.
        expect(() => buildReplayView("DODGE", omokNdjson(OMOK_MOVES), null)).toThrow()
        expect(() => buildReplayView("OMOK", dodgeNdjson([]), null)).toThrow()
    })

    it("marks my own piece so I can find myself on the board", () => {
        const omok = buildReplayView("OMOK", omokNdjson(OMOK_MOVES), "m:11")
        expect(omok.gameType === "OMOK" && omok.selfParticipantId).toBe("m:11")
    })
})

describe("replay position", () => {
    const omok = buildReplayView("OMOK", omokNdjson(OMOK_MOVES), null)
    const dodge = buildReplayView("DODGE", dodgeNdjson([]), null)

    // 오목의 한 걸음은 "수" 이고 장애물피하기의 한 걸음은 "틱" 이다. 오목의 최대값이 돌의
    // 개수인 것은 slice(0, index) 가 곧 그 시점의 판이기 때문이다 — 3수면 최대 3이다.
    it("counts stones for omok and frame indices for dodge", () => {
        expect(maxReplayIndex(omok)).toBe(3)
        expect(maxReplayIndex(dodge)).toBe(19)
    })

    it("clamps anything the slider or a stale state can produce", () => {
        expect(clampReplayIndex(omok, -5)).toBe(0)
        expect(clampReplayIndex(omok, 99)).toBe(3)
        expect(clampReplayIndex(omok, 1.4)).toBe(1)
        expect(clampReplayIndex(omok, Number.NaN)).toBe(0)
    })

    it("plays dodge at the server's own tick interval", () => {
        expect(replayStepDelayMs(dodge)).toBe(DODGE_RULES.tickMs)
        expect(replayStepDelayMs(omok)).toBe(OMOK_REPLAY_STEP_MS)
    })

    it("says which unit the numbers are in", () => {
        expect(describeReplayPosition(omok, 2)).toBe("2 / 3수")
        expect(describeReplayPosition(dodge, 5)).toBe("5 / 19틱")
        // 범위를 벗어난 값도 표시 전에 접힌다 — "25 / 19틱" 을 보여 주지 않는다.
        expect(describeReplayPosition(dodge, 25)).toBe("19 / 19틱")
    })

    it("labels the board and the grid for screen readers", () => {
        expect(describeReplayLabel(omok, 2)).toBe("오목 기보 — 2수까지")
        expect(describeReplayLabel(dodge, 0)).toBe(
            `장애물피하기 기보 ${DODGE_RULES.cols}×${DODGE_RULES.rows} — 0틱, 생존 2명, 장애물 0개`
        )
    })

    it("announces an elimination only on the tick it happened", () => {
        const last = maxReplayIndex(dodge)
        expect(describeReplayFrameEvent(dodge, 0)).toBe("")
        expect(describeReplayFrameEvent(dodge, last)).toMatch(/탈락$/)
        // 오목에는 이런 사건이 없다.
        expect(describeReplayFrameEvent(omok, 1)).toBe("")
    })

    it("marks me in the legend", () => {
        expect(describeReplayPlayerName("손님", true)).toBe("손님 (나)")
        expect(describeReplayPlayerName("손님", false)).toBe("손님")
    })
})

describe("failure messages", () => {
    // presigned URL 은 만료된다. 그건 사용자가 다시 눌러 해결할 수 있는 것이라 그렇게 말한다.
    it("tells an expired link apart from a broken one", () => {
        expect(describeReplayHttpError(403)).toMatch(/만료/)
        expect(describeReplayHttpError(404)).toMatch(/만료/)
        expect(describeReplayHttpError(500)).toMatch(/내려받지 못했습니다/)
        expect(describeReplayHttpError(500)).not.toMatch(/만료/)
    })

    it("uses the network sentence when fetch itself failed", () => {
        expect(describeReplayFailure(new TypeError("Failed to fetch"))).toBe(NETWORK_ERROR_MESSAGE)
    })

    // 파서가 던지는 문장은 영어 진단 메시지다. 그대로 화면에 올리지 않는다.
    it("never surfaces the parser's English diagnostic", () => {
        const message = describeReplayFailure(new Error("Unsupported dodge replay version 1"))
        expect(message).not.toMatch(/Unsupported/)
        expect(message).toMatch(/기보를 재생할 수 없습니다/)
    })
})

describe("result rows", () => {
    const result: GameResultSummary = {
        gameResultId: 7,
        gameType: "OMOK",
        endedAt: "2026-08-01T12:34:56.789012",
        finishRank: 2,
        winnerDisplayName: "흑돌이",
        replayAvailable: true,
    }

    it("names both games", () => {
        expect(describeGameTypeName("OMOK")).toBe("오목")
        expect(describeGameTypeName("DODGE")).toBe("장애물피하기")
    })

    it("puts the game and my rank in the title", () => {
        expect(describeResultTitle(result)).toBe("오목 · 2위")
        expect(describeResultTitle({ ...result, gameType: "DODGE", finishRank: 5 })).toBe(
            "장애물피하기 · 5위"
        )
    })

    // 서버는 승자가 없으면 COALESCE 로 빈 문자열을 준다. 그대로 이으면 "승자 " 로 끝난다.
    it("says 없음 rather than trailing off when there is no winner", () => {
        expect(describeResultSubtitle(result)).toBe("2026-08-01 12:34 · 승자 흑돌이")
        expect(describeResultSubtitle({ ...result, winnerDisplayName: "" })).toBe(
            "2026-08-01 12:34 · 승자 없음"
        )
    })

    /**
     * ended_at 은 시간대가 없는 TIMESTAMP(6) 를 문자열로 찍은 것이다. Date 로 파싱하면
     * 브라우저가 시간대를 <b>지어내고</b>, 그 뒤로는 9시간 어긋난 값이 조용히 표시된다.
     */
    it("trims the timestamp without inventing a timezone", () => {
        expect(formatEndedAt("2026-08-01T12:34:56.789012")).toBe("2026-08-01 12:34")
        expect(formatEndedAt("2026-08-01 12:34:56")).toBe("2026-08-01 12:34")
        // 읽을 수 없는 모양이면 손대지 않는다 — 잘못 자르느니 그대로 보여 준다.
        expect(formatEndedAt("나중에")).toBe("나중에")
        expect(formatEndedAt("")).toBe("")
    })

    it("offers another page only while one might exist", () => {
        expect(hasMoreResults(20, 20)).toBe(true)
        expect(hasMoreResults(19, 20)).toBe(false)
        expect(hasMoreResults(0, 20)).toBe(false)
    })
})

import { describe, expect, it } from "vitest"
import {
    OMOK_BOARD_SIZE,
    canPlaceStone,
    describeGameOutcome,
    describeOmokRejection,
    describeTurn,
    initialOmokRoomState,
    myStoneColor,
    reduceOmokRoom,
    type OmokRoomState,
} from "./omok-play"
import type { ServerMessage } from "./game-socket"
import type { ParticipantView } from "./types"

/**
 * 오목 화면의 판단을 고정한다. 이 모듈의 거의 모든 분기는 리뷰가 실제 결함을 찾아 고친
 * 자리인데(끝난 판을 되살리는 ROOM_STATE, 이어 붙는 스냅샷, 남의 금수 안내, 잠긴 채 남는
 * pendingPlaceSeq) 그것을 붙잡아 두는 테스트가 없었다. dodge-play.test.ts 와 같은 모양이다.
 *
 * <p>서버 계약의 출처는 app-webflux 의 OmokGameSink / OmokGame 이므로, 여기서 만드는 메시지는
 * 그 broadcast 호출과 같은 모양이어야 한다.
 */

function participant(id: string, name: string): ParticipantView {
    return {
        participantId: id,
        displayName: name,
        kind: id.startsWith("m:") ? "MEMBER" : "GUEST",
        ready: true,
        connection: "CONNECTED",
    }
}

const BLACK = participant("m:1", "흑돌") // 방장 = 흑
const WHITE = participant("g:abc", "백돌")

function roomState(status: "WAITING" | "IN_PROGRESS" | "FINISHED" = "WAITING"): ServerMessage {
    return {
        type: "ROOM_STATE",
        payload: {
            gameType: "OMOK",
            hostParticipantId: BLACK.participantId,
            status,
            participants: [BLACK, WHITE],
        },
    }
}

const GAME_START: ServerMessage = { type: "GAME_START", payload: { roomId: "r1" } }

function moved(
    x: number,
    y: number,
    color: "BLACK" | "WHITE",
    next?: { nextTurn: string; turnDeadline: string }
): ServerMessage {
    return {
        type: "OMOK_MOVED",
        payload: { participantId: color === "BLACK" ? "m:1" : "g:abc", x, y, color, ...next },
    }
}

function apply(state: OmokRoomState, ...messages: ServerMessage[]): OmokRoomState {
    return messages.reduce(
        (current, message) => reduceOmokRoom(current, { type: "message", message }),
        state
    )
}

const started = apply(initialOmokRoomState, roomState(), GAME_START)

describe("board size", () => {
    it("matches the server OmokBoard.SIZE", () => {
        expect(OMOK_BOARD_SIZE).toBe(15)
    })
})

describe("reduceOmokRoom / ROOM_STATE", () => {
    it("takes the roster, host and status", () => {
        const state = apply(initialOmokRoomState, roomState())

        expect(state.participants).toHaveLength(2)
        expect(state.hostParticipantId).toBe("m:1")
        expect(state.status).toBe("WAITING")
    })

    it("never downgrades a finished game back to IN_PROGRESS (known-gap G3)", () => {
        // 서버는 이제 게임이 끝나면 방을 FINISHED 로 넘기지만, 종료 신호와 경합해 늦게
        // 도착하는 IN_PROGRESS ROOM_STATE 는 여전히 있을 수 있다. 그것이 끝난 판을
        // 되살리면 canPlaceStone 이 다시 참이 된다.
        const finished = apply(started, {
            type: "GAME_END",
            payload: { winnerParticipantId: "m:1", ranks: [] },
        })

        expect(apply(finished, roomState("IN_PROGRESS")).status).toBe("FINISHED")
    })

    it("re-arms when ROOM_STATE comes back WAITING after a rematch (GAME-AC-30)", () => {
        // 재대국은 서버가 방을 WAITING 으로 되돌리며 시작된다. 위의 FINISHED 고정이
        // WAITING 까지 삼키면 재대국 방송이 와도 사이드바가 준비 단계로 돌아가지 못한다.
        const finished = apply(started, {
            type: "GAME_END",
            payload: { winnerParticipantId: "m:1", ranks: [] },
        })

        expect(apply(finished, roomState("WAITING")).status).toBe("WAITING")
    })
})

describe("reduceOmokRoom / GAME_START", () => {
    it("gives the first turn to the host, because the server gives black to the host", () => {
        // GAME_START 페이로드에는 roomId 뿐이다. 이 규칙이 없으면 turnParticipantId 가 null 로
        // 남아 두 사람 모두 판이 잠긴 채 첫 수를 둘 수 없다.
        expect(started.turnParticipantId).toBe("m:1")
        expect(started.status).toBe("IN_PROGRESS")
    })

    it("leaves the first deadline empty rather than guessing it", () => {
        expect(started.turnDeadline).toBeNull()
    })

    it("clears the previous game's board, outcome, notice and pending place", () => {
        const dirty = apply(
            initialOmokRoomState,
            roomState(),
            GAME_START,
            moved(7, 7, "BLACK", { nextTurn: "g:abc", turnDeadline: "2026-08-01T00:01:00Z" }),
            { type: "GAME_END", payload: { winnerParticipantId: "m:1", ranks: [] } }
        )
        const restarted = apply(dirty, GAME_START)

        expect(restarted.placements).toEqual([])
        expect(restarted.outcome).toBeNull()
        expect(restarted.notice).toBeNull()
        expect(restarted.pendingPlaceSeq).toBeNull()
    })
})

describe("reduceOmokRoom / OMOK_MOVED", () => {
    it("appends the stone and follows the server's next turn", () => {
        const state = apply(
            started,
            moved(7, 7, "BLACK", { nextTurn: "g:abc", turnDeadline: "2026-08-01T00:01:00Z" })
        )

        expect(state.placements).toEqual([{ x: 7, y: 7, color: "BLACK" }])
        expect(state.turnParticipantId).toBe("g:abc")
        expect(state.turnDeadline).toBe("2026-08-01T00:01:00Z")
    })

    it("ends the turn entirely on a winning move, which carries no nextTurn", () => {
        // 승리 착수에는 nextTurn·turnDeadline 이 아예 오지 않는다. 이 분기를 놓치면 판은
        // 끝났는데 차례 표시만 살아 있는 화면이 된다.
        const state = apply(
            started,
            moved(7, 7, "BLACK", { nextTurn: "g:abc", turnDeadline: "2026-08-01T00:01:00Z" }),
            moved(8, 8, "WHITE")
        )

        expect(state.placements).toHaveLength(2)
        expect(state.turnParticipantId).toBeNull()
        expect(state.turnDeadline).toBeNull()
    })

    it("releases the round-trip lock", () => {
        const sent = reduceOmokRoom(started, { type: "place-sent", seq: 4 })
        expect(sent.pendingPlaceSeq).toBe(4)

        expect(apply(sent, moved(7, 7, "BLACK")).pendingPlaceSeq).toBeNull()
    })
})

describe("reduceOmokRoom / OMOK_REJECTED", () => {
    const sent = reduceOmokRoom(started, { type: "place-sent", seq: 4 })

    it("shows the reason for my own rejected move", () => {
        const state = apply(sent, {
            type: "OMOK_REJECTED",
            ackSeq: 4,
            payload: { reason: "DOUBLE_THREE" },
        })

        expect(state.notice).toBe("삼삼은 흑의 금수입니다.")
        expect(state.pendingPlaceSeq).toBeNull()
    })

    it("stays silent about someone else's rejection, which is broadcast room-wide", () => {
        // OmokGameSink 는 OMOK_REJECTED 를 방 전체로 브로드캐스트한다. ackSeq 로 거르지 않으면
        // 상대가 금수를 뒀을 때 내 화면에 내가 하지도 않은 일의 안내가 뜬다.
        const other = apply(sent, {
            type: "OMOK_REJECTED",
            ackSeq: 9,
            payload: { reason: "DOUBLE_THREE" },
        })

        expect(other.notice).toBeNull()
        expect(other.pendingPlaceSeq).toBe(4)
    })

    it("ignores a rejection when nothing of mine is in flight", () => {
        const state = apply(started, {
            type: "OMOK_REJECTED",
            ackSeq: 4,
            payload: { reason: "OCCUPIED" },
        })

        expect(state.notice).toBeNull()
    })
})

describe("reduceOmokRoom / GAME_SNAPSHOT", () => {
    const played = apply(
        started,
        moved(7, 7, "BLACK", { nextTurn: "g:abc", turnDeadline: "2026-08-01T00:01:00Z" })
    )

    const snapshot: ServerMessage = {
        type: "GAME_SNAPSHOT",
        payload: {
            gameType: "OMOK",
            moves: [
                { x: 0, y: 0, color: "BLACK" },
                { x: 1, y: 1, color: "WHITE" },
            ],
            nextTurn: "m:1",
            turnDeadline: "2026-08-01T00:02:00Z",
        },
    }

    it("replaces the board instead of appending to it", () => {
        // 끊긴 동안 놓친 수가 있으므로 이어 붙이면 판이 어긋난다. 통째로 갈아 끼운다.
        const state = apply(played, snapshot)

        expect(state.placements).toEqual([
            { x: 0, y: 0, color: "BLACK" },
            { x: 1, y: 1, color: "WHITE" },
        ])
        expect(state.turnParticipantId).toBe("m:1")
        expect(state.turnDeadline).toBe("2026-08-01T00:02:00Z")
    })

    it("keeps my notice, because any player's reload broadcasts a snapshot to everyone", () => {
        const noticed = apply(
            reduceOmokRoom(played, { type: "place-sent", seq: 1 }),
            { type: "OMOK_REJECTED", ackSeq: 1, payload: { reason: "OCCUPIED" } }
        )
        expect(noticed.notice).toBeTruthy()

        // 스냅샷은 방 전체로 나간다. 여기서 지우면 상대가 새로고침하는 것만으로 내 금수
        // 안내가 사라진다.
        expect(apply(noticed, snapshot).notice).toBe(noticed.notice)
    })

    it("clears the in-flight lock so a lost response cannot freeze the board", () => {
        const sent = reduceOmokRoom(played, { type: "place-sent", seq: 4 })
        expect(apply(sent, snapshot).pendingPlaceSeq).toBeNull()
    })

    it("ignores a DODGE snapshot", () => {
        const state = apply(played, {
            type: "GAME_SNAPSHOT",
            payload: { gameType: "DODGE", tick: 3, positions: [], obstacles: [] },
        })

        expect(state).toEqual(played)
    })
})

describe("reduceOmokRoom / ERROR and lifecycle", () => {
    it("shows the mapped code, never the English payload.message", () => {
        const state = apply(started, {
            type: "ERROR",
            payload: { code: "game_roomFull", status: 400, message: "Room is full" },
        })

        expect(state.notice).toBeTruthy()
        expect(state.notice).not.toContain("Room is full")
    })

    it("clears the in-flight lock only when the error answers my own command", () => {
        // seq 는 클라이언트마다 0 부터 따로 센다. 응답을 이미 받은 seq 를 남겨 두면 나중에
        // 상대의 같은 번호가 내 화면에 상대의 금수 안내를 띄운다.
        const sent = reduceOmokRoom(started, { type: "place-sent", seq: 4 })

        expect(apply(sent, { type: "ERROR", ackSeq: 4, payload: { message: "x" } }).pendingPlaceSeq)
            .toBeNull()
        expect(apply(sent, { type: "ERROR", ackSeq: 9, payload: { message: "x" } }).pendingPlaceSeq)
            .toBe(4)
    })

    it("records the outcome and stops the clock on GAME_END", () => {
        const state = apply(started, {
            type: "GAME_END",
            payload: { winnerParticipantId: "m:1", ranks: [] },
        })

        expect(state.status).toBe("FINISHED")
        expect(state.turnParticipantId).toBeNull()
        expect(state.turnDeadline).toBeNull()
        expect(state.outcome).not.toBeNull()
    })

    it("passes unknown message types through and resets cleanly", () => {
        expect(apply(started, { type: "SOMETHING_NEW", payload: {} })).toEqual(started)
        expect(reduceOmokRoom(started, { type: "reset" })).toEqual(initialOmokRoomState)
    })
})

describe("canPlaceStone", () => {
    const myTurn = apply(started, roomState("IN_PROGRESS"))

    it("requires joined — open is only the handshake and the server drops pre-JOIN frames", () => {
        expect(canPlaceStone(myTurn, "m:1", "joined")).toBe(true)
        expect(canPlaceStone(myTurn, "m:1", "open")).toBe(false)
        expect(canPlaceStone(myTurn, "m:1", "connecting")).toBe(false)
        expect(canPlaceStone(myTurn, "m:1", "reconnecting")).toBe(false)
        expect(canPlaceStone(myTurn, "m:1", "closed")).toBe(false)
        expect(canPlaceStone(myTurn, "m:1", "rejected")).toBe(false)
    })

    it("is false on the opponent's turn, outside a running game, and without an identity", () => {
        expect(canPlaceStone(myTurn, "g:abc", "joined")).toBe(false)
        expect(canPlaceStone(myTurn, null, "joined")).toBe(false)
        expect(canPlaceStone(apply(initialOmokRoomState, roomState()), "m:1", "joined")).toBe(false)

        const ended = apply(myTurn, {
            type: "GAME_END",
            payload: { winnerParticipantId: "m:1", ranks: [] },
        })
        expect(canPlaceStone(ended, "m:1", "joined")).toBe(false)
    })
})

describe("myStoneColor", () => {
    it("gives black to the host and white to the other player", () => {
        expect(myStoneColor(started, "m:1")).toBe("BLACK")
        expect(myStoneColor(started, "g:abc")).toBe("WHITE")
    })

    it("says nothing for an identity that is not actually in the room", () => {
        // resolveSelfParticipantId 는 명단에 없는 후보를 돌려줄 수 있다(낡은 authMemberId).
        // 그때 "내 돌 백" 은 근거 없는 단정이다.
        expect(myStoneColor(started, "m:999")).toBeNull()
        expect(myStoneColor(started, null)).toBeNull()
        expect(myStoneColor(initialOmokRoomState, "m:1")).toBeNull()
    })
})

describe("turn and outcome text", () => {
    it("says nothing outside a running game", () => {
        expect(describeTurn(initialOmokRoomState, true)).toBe("")
    })

    it("names whose turn it is and appends the server deadline when there is one", () => {
        expect(describeTurn(started, true)).toBe("내 차례")
        expect(describeTurn(started, false)).toBe("상대 차례")

        const withDeadline = apply(
            started,
            moved(7, 7, "BLACK", { nextTurn: "g:abc", turnDeadline: "2026-08-01T00:01:00Z" })
        )
        expect(describeTurn(withDeadline, false)).toContain("제한")
    })

    it("describes the outcome from my point of view", () => {
        const ranks = [
            { participantId: "m:1", displayName: "흑돌", rank: 1 },
            { participantId: "g:abc", displayName: "백돌", rank: 2 },
        ]

        expect(describeGameOutcome({ winnerParticipantId: "m:1", ranks }, "m:1")).toContain("승리")
        expect(describeGameOutcome({ winnerParticipantId: "m:1", ranks }, "g:abc")).toContain("흑돌")
        // 승자 없음은 null 이 아니라 빈 문자열로 온다(OmokGameSink).
        expect(describeGameOutcome({ winnerParticipantId: "", ranks: [] }, "m:1")).toContain(
            "승부가 나지 않았습니다"
        )
    })

    it("has Korean text for every rejection reason the server can send", () => {
        for (const reason of [
            "GAME_FINISHED",
            "NOT_YOUR_TURN",
            "OUT_OF_BOUNDS",
            "OCCUPIED",
            "DOUBLE_THREE",
            "DOUBLE_FOUR",
            "OVERLINE",
        ]) {
            expect(describeOmokRejection(reason)).not.toBe("둘 수 없는 자리입니다.")
        }
        expect(describeOmokRejection("SOMETHING_NEW")).toBe("둘 수 없는 자리입니다.")
    })
})

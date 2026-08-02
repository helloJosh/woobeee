import { describe, expect, it } from "vitest"
import {
    DODGE_PLAYER_COLOR_COUNT,
    appendColorOrder,
    canMoveInDodge,
    colorIndexOf,
    describeDodgeOutcome,
    describeDodgeProgress,
    directionForKey,
    initialDodgeRoomState,
    isSelfEliminated,
    isTypingElement,
    reduceDodgeRoom,
    shouldSendMove,
    sortedRanks,
    toGridPlayers,
    toRoster,
    type DodgeRoomState,
} from "./dodge-play"
import type { ServerMessage } from "./game-socket"
import type { ParticipantView } from "./types"

/**
 * 장애물피하기 화면의 판단을 고정한다. 리듀서(서버 메시지 → 화면 상태)와, 화면이 그 상태에서
 * 읽어 내는 파생값들이다. 서버 계약의 출처는 app-webflux 의 DodgeGameSink 이므로, 여기서
 * 만드는 메시지는 그 broadcast 호출과 같은 모양이어야 한다.
 */

function participant(id: string, name: string, ready = true): ParticipantView {
    return {
        participantId: id,
        displayName: name,
        kind: id.startsWith("m:") ? "MEMBER" : "GUEST",
        ready,
        connection: "CONNECTED",
    }
}

function roomState(participants: ParticipantView[], host = participants[0]?.participantId ?? ""): ServerMessage {
    return {
        type: "ROOM_STATE",
        payload: {
            gameType: "DODGE",
            hostParticipantId: host,
            status: "WAITING",
            participants,
        },
    }
}

function tickMessage(
    tick: number,
    positions: { participantId: string; x: number; y: number }[],
    obstacles: { x: number; y: number }[] = [],
    eliminated: string[] = []
): ServerMessage {
    return { type: "DODGE_TICK", payload: { tick, positions, obstacles, eliminated } }
}

function apply(state: DodgeRoomState, ...messages: ServerMessage[]): DodgeRoomState {
    return messages.reduce((current, message) => reduceDodgeRoom(current, { type: "message", message }), state)
}

const HOST = participant("m:1", "방장")
const GUEST = participant("g:abc", "손님")

describe("reduceDodgeRoom / ROOM_STATE", () => {
    it("takes the roster, host and status from the payload", () => {
        const state = apply(initialDodgeRoomState, roomState([HOST, GUEST]))

        expect(state.participants).toHaveLength(2)
        expect(state.hostParticipantId).toBe("m:1")
        expect(state.status).toBe("WAITING")
    })

    it("keeps a finished game finished (the server leaves the room IN_PROGRESS — known-gap G3)", () => {
        const finished = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            { type: "GAME_START", payload: { roomId: "r1" } },
            { type: "GAME_END", payload: { winnerParticipantId: "m:1", ranks: [] } }
        )

        const afterLateRoomState = apply(finished, {
            type: "ROOM_STATE",
            payload: {
                gameType: "DODGE",
                hostParticipantId: "m:1",
                status: "IN_PROGRESS",
                participants: [HOST, GUEST],
            },
        })

        expect(afterLateRoomState.status).toBe("FINISHED")
    })
})

describe("reduceDodgeRoom / frames", () => {
    it("replaces the frame on DODGE_TICK", () => {
        const state = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            tickMessage(1, [{ participantId: "m:1", x: 3, y: 15 }], [{ x: 0, y: 0 }]),
            tickMessage(2, [{ participantId: "m:1", x: 3, y: 14 }], [{ x: 0, y: 1 }])
        )

        expect(state.tick).toBe(2)
        expect(state.positions).toEqual([{ participantId: "m:1", x: 3, y: 14 }])
        expect(state.obstacles).toEqual([{ x: 0, y: 1 }])
        expect(state.frameSeen).toBe(true)
    })

    it("replaces the frame on a DODGE snapshot (the only way a reconnect gets it back)", () => {
        const state = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            tickMessage(2, [{ participantId: "m:1", x: 3, y: 14 }]),
            {
                type: "GAME_SNAPSHOT",
                payload: {
                    gameType: "DODGE",
                    tick: 40,
                    positions: [{ participantId: "g:abc", x: 9, y: 15 }],
                    obstacles: [{ x: 4, y: 7 }],
                },
            }
        )

        expect(state.tick).toBe(40)
        expect(state.positions).toEqual([{ participantId: "g:abc", x: 9, y: 15 }])
        expect(state.obstacles).toEqual([{ x: 4, y: 7 }])
    })

    it("ignores an OMOK snapshot without touching the frame", () => {
        const before = apply(initialDodgeRoomState, roomState([HOST, GUEST]), tickMessage(7, []))
        const after = apply(before, {
            type: "GAME_SNAPSHOT",
            payload: {
                gameType: "OMOK",
                moves: [{ x: 1, y: 1, color: "BLACK" }],
                nextTurn: "g:abc",
                turnDeadline: "2026-08-01T00:00:00Z",
            },
        })

        expect(after.tick).toBe(7)
        expect(after).toEqual(before)
    })

    it("clears the previous frame on GAME_START instead of guessing the starting cells", () => {
        const state = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            tickMessage(30, [{ participantId: "m:1", x: 1, y: 1 }], [{ x: 2, y: 2 }]),
            { type: "GAME_START", payload: { roomId: "r1" } }
        )

        expect(state.status).toBe("IN_PROGRESS")
        expect(state.tick).toBe(0)
        expect(state.positions).toEqual([])
        expect(state.obstacles).toEqual([])
        expect(state.frameSeen).toBe(false)
    })

    it("keeps the last frame on GAME_END so the final board stays on screen", () => {
        const state = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            tickMessage(30, [{ participantId: "m:1", x: 1, y: 1 }], [{ x: 2, y: 2 }]),
            { type: "GAME_END", payload: { winnerParticipantId: "m:1", ranks: [] } }
        )

        expect(state.status).toBe("FINISHED")
        expect(state.positions).toHaveLength(1)
        expect(state.obstacles).toHaveLength(1)
    })
})

describe("reduceDodgeRoom / notices", () => {
    it("shows the mapped message for the error code, never the English payload.message", () => {
        const state = apply(initialDodgeRoomState, {
            type: "ERROR",
            payload: { code: "game_roomFull", status: 400, message: "Room is full" },
        })

        expect(state.notice).toBeTruthy()
        expect(state.notice).not.toContain("Room is full")
    })

    it("does not let a 100ms tick wipe a notice before it can be read", () => {
        const state = apply(
            initialDodgeRoomState,
            { type: "ERROR", payload: { code: "game_roomFull", message: "Room is full" } },
            tickMessage(1, [])
        )

        expect(state.notice).toBeTruthy()
    })

    it("does not let another player's reconnect snapshot wipe the notice", () => {
        const state = apply(
            initialDodgeRoomState,
            { type: "ERROR", payload: { code: "game_roomFull", message: "Room is full" } },
            {
                type: "GAME_SNAPSHOT",
                payload: { gameType: "DODGE", tick: 5, positions: [], obstacles: [] },
            }
        )

        expect(state.notice).toBeTruthy()
    })

    it("passes unknown message types through untouched", () => {
        const before = apply(initialDodgeRoomState, roomState([HOST]))
        expect(apply(before, { type: "SOMETHING_NEW", payload: { a: 1 } })).toEqual(before)
    })

    it("resets to the initial state so a new room never inherits the old frame", () => {
        const state = apply(initialDodgeRoomState, roomState([HOST, GUEST]), tickMessage(9, []))
        expect(reduceDodgeRoom(state, { type: "reset" })).toEqual(initialDodgeRoomState)
    })
})

describe("colour assignment", () => {
    it("appends only unseen ids and keeps the array identity when there is nothing to add", () => {
        const order = appendColorOrder([], ["a", "b"])
        expect(order).toEqual(["a", "b"])
        expect(appendColorOrder(order, ["a", "b"])).toBe(order)
        expect(appendColorOrder(order, ["b", "c"])).toEqual(["a", "b", "c"])
    })

    it("never moves a colour once assigned, even when someone leaves the roster", () => {
        const joined = apply(initialDodgeRoomState, roomState([HOST, GUEST, participant("g:x", "셋째")]))
        const hostColor = colorIndexOf(joined.colorOrder, "m:1")
        const thirdColor = colorIndexOf(joined.colorOrder, "g:x")

        // 가운데 사람이 나간다. 명단 인덱스를 그대로 색으로 썼다면 셋째의 색이 한 칸 당겨진다.
        const afterLeave = apply(joined, roomState([HOST, participant("g:x", "셋째")]))

        expect(colorIndexOf(afterLeave.colorOrder, "m:1")).toBe(hostColor)
        expect(colorIndexOf(afterLeave.colorOrder, "g:x")).toBe(thirdColor)
    })

    it("wraps past the palette and falls back to 0 for an unknown id", () => {
        const order = Array.from({ length: DODGE_PLAYER_COLOR_COUNT + 1 }, (_, i) => `p${i}`)
        expect(colorIndexOf(order, `p${DODGE_PLAYER_COLOR_COUNT}`)).toBe(0)
        expect(colorIndexOf(order, "nobody")).toBe(0)
    })
})

describe("toGridPlayers / toRoster", () => {
    const played = apply(
        initialDodgeRoomState,
        roomState([HOST, GUEST]),
        { type: "GAME_START", payload: { roomId: "r1" } },
        tickMessage(4, [{ participantId: "g:abc", x: 9, y: 15 }])
    )

    it("marks the self marker and resolves display names", () => {
        const players = toGridPlayers(played, "g:abc")

        expect(players).toHaveLength(1)
        expect(players[0].displayName).toBe("손님")
        expect(players[0].isSelf).toBe(true)
        expect(toGridPlayers(played, "m:1")[0].isSelf).toBe(false)
    })

    it("falls back to the participant id when the frame arrives before the roster", () => {
        const orphan = apply(initialDodgeRoomState, tickMessage(1, [{ participantId: "g:zzz", x: 0, y: 0 }]))
        expect(toGridPlayers(orphan, null)[0].displayName).toBe("g:zzz")
    })

    it("reports everyone as alive before the first frame and marks the missing ones after", () => {
        const started = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            { type: "GAME_START", payload: { roomId: "r1" } }
        )
        expect(toRoster(started, "m:1").every((entry) => entry.alive)).toBe(true)

        const roster = toRoster(played, "m:1")
        expect(roster.find((entry) => entry.participantId === "m:1")?.alive).toBe(false)
        expect(roster.find((entry) => entry.participantId === "g:abc")?.alive).toBe(true)
    })
})

describe("isSelfEliminated", () => {
    const base = apply(
        initialDodgeRoomState,
        roomState([HOST, GUEST]),
        { type: "GAME_START", payload: { roomId: "r1" } }
    )

    it("is false before the first frame even though positions are empty", () => {
        expect(isSelfEliminated(base, "m:1")).toBe(false)
    })

    it("is true once the server frame stops carrying me", () => {
        const state = apply(base, tickMessage(1, [{ participantId: "g:abc", x: 1, y: 1 }]))
        expect(isSelfEliminated(state, "m:1")).toBe(true)
        expect(isSelfEliminated(state, "g:abc")).toBe(false)
    })

    it("does not declare an unresolved identity eliminated", () => {
        const state = apply(base, tickMessage(1, [{ participantId: "g:abc", x: 1, y: 1 }]))
        expect(isSelfEliminated(state, null)).toBe(false)
        expect(isSelfEliminated(state, "m:999")).toBe(false)
    })
})

describe("canMoveInDodge", () => {
    const playing = apply(
        initialDodgeRoomState,
        roomState([HOST, GUEST]),
        { type: "GAME_START", payload: { roomId: "r1" } },
        tickMessage(1, [
            { participantId: "m:1", x: 2, y: 15 },
            { participantId: "g:abc", x: 9, y: 15 },
        ])
    )

    it("requires joined — open is only the handshake and the server drops pre-JOIN frames", () => {
        expect(canMoveInDodge(playing, "m:1", "joined")).toBe(true)
        expect(canMoveInDodge(playing, "m:1", "open")).toBe(false)
        expect(canMoveInDodge(playing, "m:1", "reconnecting")).toBe(false)
        expect(canMoveInDodge(playing, "m:1", "closed")).toBe(false)
    })

    it("is false while waiting, after the game ends, and once eliminated", () => {
        const waiting = apply(initialDodgeRoomState, roomState([HOST, GUEST]))
        expect(canMoveInDodge(waiting, "m:1", "joined")).toBe(false)

        const ended = apply(playing, { type: "GAME_END", payload: { winnerParticipantId: "g:abc", ranks: [] } })
        expect(canMoveInDodge(ended, "m:1", "joined")).toBe(false)

        const dead = apply(playing, tickMessage(2, [{ participantId: "g:abc", x: 9, y: 14 }]))
        expect(canMoveInDodge(dead, "m:1", "joined")).toBe(false)
        expect(canMoveInDodge(dead, "g:abc", "joined")).toBe(true)
    })

    it("is false without a resolved identity", () => {
        expect(canMoveInDodge(playing, null, "joined")).toBe(false)
    })
})

describe("keyboard input", () => {
    it("maps arrows and WASD in both cases", () => {
        expect(directionForKey("ArrowUp")).toBe("UP")
        expect(directionForKey("ArrowDown")).toBe("DOWN")
        expect(directionForKey("ArrowLeft")).toBe("LEFT")
        expect(directionForKey("ArrowRight")).toBe("RIGHT")
        expect(directionForKey("w")).toBe("UP")
        expect(directionForKey("A")).toBe("LEFT")
        expect(directionForKey("S")).toBe("DOWN")
        expect(directionForKey("d")).toBe("RIGHT")
    })

    it("returns null for everything else, and does not lowercase named keys into a match", () => {
        expect(directionForKey("Enter")).toBeNull()
        expect(directionForKey("ㅈ")).toBeNull()
        expect(directionForKey("arrowup")).toBeNull()
    })

    it("recognises text entry targets so the invite field keeps its arrow keys", () => {
        expect(isTypingElement({ tagName: "INPUT" })).toBe(true)
        expect(isTypingElement({ tagName: "textarea" })).toBe(true)
        expect(isTypingElement({ tagName: "SELECT" })).toBe(true)
        expect(isTypingElement({ tagName: "DIV", isContentEditable: true })).toBe(true)
        expect(isTypingElement({ tagName: "DIV" })).toBe(false)
        expect(isTypingElement(null)).toBe(false)
    })
})

describe("shouldSendMove", () => {
    it("drops key auto-repeat of the same direction inside one tick", () => {
        const sent = { tick: 12, direction: "LEFT" } as const
        expect(shouldSendMove(sent, { tick: 12, direction: "LEFT" })).toBe(false)
    })

    it("allows a new tick, a new direction, and the very first move", () => {
        const sent = { tick: 12, direction: "LEFT" } as const
        expect(shouldSendMove(sent, { tick: 13, direction: "LEFT" })).toBe(true)
        expect(shouldSendMove(sent, { tick: 12, direction: "RIGHT" })).toBe(true)
        expect(shouldSendMove(null, { tick: 12, direction: "LEFT" })).toBe(true)
    })

    it("keeps letting the same move through while nothing was recorded as sent", () => {
        // send 가 null 을 돌려준 프레임(소켓이 닫혀 있었다)은 기록되지 않으므로, 다음 시도가
        // 막히지 않는다는 것이 이 잠금의 유일한 안전 조건이다.
        expect(shouldSendMove(null, { tick: 12, direction: "LEFT" })).toBe(true)
        expect(shouldSendMove(null, { tick: 12, direction: "LEFT" })).toBe(true)
    })
})

describe("status text", () => {
    const playing = apply(
        initialDodgeRoomState,
        roomState([HOST, GUEST]),
        { type: "GAME_START", payload: { roomId: "r1" } },
        tickMessage(31, [
            { participantId: "m:1", x: 2, y: 15 },
            { participantId: "g:abc", x: 9, y: 15 },
        ])
    )

    it("says nothing outside a running game", () => {
        expect(describeDodgeProgress(initialDodgeRoomState, false)).toBe("")
    })

    it("does not count survivors before the first frame arrives", () => {
        const justStarted = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            { type: "GAME_START", payload: { roomId: "r1" } }
        )
        expect(describeDodgeProgress(justStarted, false)).not.toContain("생존 0명")
    })

    it("shows the survivor count and tick, and swaps the hint once eliminated", () => {
        expect(describeDodgeProgress(playing, false)).toBe("방향키 또는 WASD 로 이동 · 생존 2명 · 31틱")
        expect(describeDodgeProgress(playing, true)).toBe("탈락 — 관전 중 · 생존 2명 · 31틱")
    })

    it("describes the outcome from my point of view", () => {
        const ranks = [
            { participantId: "g:abc", displayName: "손님", rank: 1 },
            { participantId: "m:1", displayName: "방장", rank: 2 },
        ]

        expect(describeDodgeOutcome({ winnerParticipantId: "g:abc", ranks }, "g:abc")).toBe(
            "게임 종료 — 1위입니다!"
        )
        expect(describeDodgeOutcome({ winnerParticipantId: "g:abc", ranks }, "m:1")).toBe(
            "게임 종료 — 1위 손님 · 내 순위 2위"
        )
        // 관전자(명단에 없는 신원)에게는 남의 등수를 지어내지 않는다.
        expect(describeDodgeOutcome({ winnerParticipantId: "g:abc", ranks }, null)).toBe(
            "게임 종료 — 1위 손님"
        )
        // 승자 없음은 null 이 아니라 빈 문자열로 온다(DodgeGameSink).
        expect(describeDodgeOutcome({ winnerParticipantId: "", ranks: [] }, "m:1")).toBe(
            "게임 종료 — 승자가 없습니다."
        )
    })

    it("sorts the rank table by rank without mutating the payload", () => {
        const ranks = [
            { participantId: "m:1", displayName: "방장", rank: 3 },
            { participantId: "g:abc", displayName: "손님", rank: 1 },
            { participantId: "g:xyz", displayName: "셋째", rank: 2 },
        ]
        const outcome = { winnerParticipantId: "g:abc", ranks }

        expect(sortedRanks(outcome).map((entry) => entry.rank)).toEqual([1, 2, 3])
        expect(ranks[0].rank).toBe(3)
    })
})

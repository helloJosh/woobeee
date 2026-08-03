import { describe, expect, it, vi } from "vitest"
import {
    DODGE_PLAYER_COLOR_COUNT,
    MOVE_MIN_INTERVAL_MS,
    appendColorOrder,
    attemptMove,
    canMoveInDodge,
    colorIndexOf,
    describeDodgeOutcome,
    describeDodgeProgress,
    describeGridLabel,
    describeStackBadge,
    directionForKey,
    initialDodgeRoomState,
    isSelfEliminated,
    isTypingElement,
    playerNumberOf,
    reduceDodgeRoom,
    shouldSendMove,
    sortedRanks,
    toGridPlayers,
    toRoster,
    type DodgeRoomState,
} from "./dodge-play"
import { DODGE_RULES } from "./dodge-engine"
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

    it("re-arms when ROOM_STATE comes back WAITING after a rematch (GAME-AC-30)", () => {
        // 재대국은 서버가 방을 WAITING 으로 되돌리며 시작된다. FINISHED 고정이 WAITING 까지
        // 삼키면 재대국 방송이 와도 사이드바가 준비 단계로 돌아가지 못한다.
        const finished = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            { type: "GAME_START", payload: { roomId: "r1" } },
            { type: "GAME_END", payload: { winnerParticipantId: "m:1", ranks: [] } }
        )

        expect(apply(finished, roomState([HOST, GUEST])).status).toBe("WAITING")
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

    it("does NOT wrap the drawn number — the ninth player must not read as the first", () => {
        // 정원이 8명이어도 한 방을 거쳐 간 사람은 더 많을 수 있다(한 명 나가고 한 명 들어오기,
        // sessionStorage 를 잃은 게스트의 새 g:<uuid>). 색은 돌아도 번호는 돌면 안 된다.
        const order = Array.from({ length: DODGE_PLAYER_COLOR_COUNT + 1 }, (_, i) => `p${i}`)
        const ninth = `p${DODGE_PLAYER_COLOR_COUNT}`

        expect(colorIndexOf(order, ninth)).toBe(colorIndexOf(order, "p0"))
        expect(playerNumberOf(order, ninth)).toBe(DODGE_PLAYER_COLOR_COUNT + 1)
        expect(playerNumberOf(order, "p0")).toBe(1)
        expect(playerNumberOf(order, ninth)).not.toBe(playerNumberOf(order, "p0"))
    })

    it("reports 0 for an id it has never assigned, so the screen can say '?' instead of '1'", () => {
        expect(playerNumberOf(["a"], "nobody")).toBe(0)
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

    it("clamps an out-of-range position onto the board instead of dropping the marker", () => {
        // 서버가 오늘은 범위를 지키지만, 어긋난 좌표가 오면 격자는 그 말을 아무 데도 그리지
        // 않으면서 "생존 N명" 에는 계속 센다 — 조용히 사라지는 것이 가장 나쁜 실패다.
        const strange = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            tickMessage(1, [
                { participantId: "m:1", x: -3, y: 99 },
                { participantId: "g:abc", x: 999, y: -1 },
            ])
        )
        const players = toGridPlayers(strange, "m:1")

        expect(players).toHaveLength(2)
        expect(players[0]).toMatchObject({ x: 0, y: DODGE_RULES.rows - 1 })
        expect(players[1]).toMatchObject({ x: DODGE_RULES.cols - 1, y: 0 })
        expect(players.every((p) => Number.isInteger(p.x) && Number.isInteger(p.y))).toBe(true)
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
    it("thins auto-repeat of the same direction inside the interval", () => {
        const sent = { sentAt: 1_000, direction: "LEFT" } as const
        expect(shouldSendMove(sent, { sentAt: 1_010, direction: "LEFT" })).toBe(false)
        expect(shouldSendMove(sent, { sentAt: 1_000 + MOVE_MIN_INTERVAL_MS, direction: "LEFT" })).toBe(true)
    })

    it("lets a direction change through immediately — turning is the reflex that matters", () => {
        const sent = { sentAt: 1_000, direction: "LEFT" } as const
        expect(shouldSendMove(sent, { sentAt: 1_001, direction: "RIGHT" })).toBe(true)
    })

    it("keeps the interval shorter than a server tick so no tick goes unfed", () => {
        expect(MOVE_MIN_INTERVAL_MS).toBeLessThan(DODGE_RULES.tickMs)
    })

    it("does not freeze a held key while a frame is late", () => {
        // 이 케이스가 이 함수를 벽시계 시간에 묶어 두는 이유다. 예전에는 state.tick 으로
        // 접었는데, 틱 번호는 DODGE_TICK 이 도착해야만 오른다 — 프레임이 150ms 늦으면 그
        // 창 안의 자동 반복이 전부 눌려 말이 제자리에 선다. 시간은 프레임과 무관하게 흐른다.
        const sent = { sentAt: 1_000, direction: "LEFT" } as const
        expect(shouldSendMove(sent, { sentAt: 1_150, direction: "LEFT" })).toBe(true)
    })

    it("always allows the first move", () => {
        expect(shouldSendMove(null, { sentAt: 0, direction: "LEFT" })).toBe(true)
    })
})

describe("attemptMove", () => {
    const playing = apply(
        initialDodgeRoomState,
        roomState([HOST, GUEST]),
        { type: "GAME_START", payload: { roomId: "r1" } },
        tickMessage(1, [
            { participantId: "m:1", x: 2, y: 15 },
            { participantId: "g:abc", x: 9, y: 15 },
        ])
    )

    function attempt(overrides: Partial<Parameters<typeof attemptMove>[0]> = {}) {
        const send = overrides.send ?? vi.fn(() => 7)
        return {
            send,
            result: attemptMove({
                state: playing,
                selfParticipantId: "m:1",
                socketStatus: "joined",
                lastSent: null,
                direction: "LEFT",
                now: 1_000,
                send,
                ...overrides,
            }),
        }
    }

    it("sends and records when everything is in order", () => {
        const { send, result } = attempt()

        expect(send).toHaveBeenCalledWith("LEFT")
        expect(result).toEqual({ sent: true, lastSent: { sentAt: 1_000, direction: "LEFT" } })
    })

    /**
     * 이 두 케이스가 이 함수가 존재하는 이유다. GameSocket.send 는 소켓이 열려 있지 않으면
     * 프레임을 버리고 null 을 돌려준다. 그것을 "보냈다" 로 기록하면 그 뒤 간격 동안 같은
     * 방향이 막혀 재접속 직후의 첫 입력이 사라진다. 판단이 컴포넌트 안에 있으면 이 단언을
     * 쓸 수 없다.
     */
    it("does not record a dropped frame, so the very next attempt still goes out", () => {
        const dropped = vi.fn(() => null)
        const first = attempt({ send: dropped }).result

        expect(first).toEqual({ sent: false, lastSent: null })

        const second = attemptMove({
            state: playing,
            selfParticipantId: "m:1",
            socketStatus: "joined",
            lastSent: first.lastSent,
            direction: "LEFT",
            now: 1_001, // 간격보다 훨씬 짧다. 기록이 남았다면 여기서 막힌다.
            send: dropped,
        })

        expect(dropped).toHaveBeenCalledTimes(2)
        expect(second.sent).toBe(false)
    })

    it("keeps the previous record when the send is dropped mid-run", () => {
        const previous = { sentAt: 900, direction: "UP" } as const
        const { result } = attempt({ send: vi.fn(() => null), lastSent: previous })

        expect(result.lastSent).toBe(previous)
    })

    it("does not even call send when the gate is shut", () => {
        const send = vi.fn(() => 7)

        expect(attempt({ send, socketStatus: "open" }).result.sent).toBe(false)
        expect(attempt({ send, socketStatus: "reconnecting" }).result.sent).toBe(false)
        expect(attempt({ send, selfParticipantId: null }).result.sent).toBe(false)
        expect(attempt({ send, state: initialDodgeRoomState }).result.sent).toBe(false)
        // 탈락자는 관전자다.
        expect(
            attempt({
                send,
                state: apply(playing, tickMessage(2, [{ participantId: "g:abc", x: 9, y: 14 }])),
            }).result.sent
        ).toBe(false)
        expect(send).not.toHaveBeenCalled()
    })

    it("does not call send when the interval has not elapsed", () => {
        const send = vi.fn(() => 7)
        const { result } = attempt({
            send,
            lastSent: { sentAt: 990, direction: "LEFT" },
            now: 1_000,
        })

        expect(send).not.toHaveBeenCalled()
        expect(result.sent).toBe(false)
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

    it("does not let the grid's accessible name count survivors before the first frame", () => {
        const justStarted = apply(
            initialDodgeRoomState,
            roomState([HOST, GUEST]),
            { type: "GAME_START", payload: { roomId: "r1" } }
        )

        // 눈에 보이는 안내는 "기다리는 중" 인데 스크린리더만 "생존 0명" 을 듣는 일이 없어야 한다.
        expect(describeGridLabel(justStarted)).not.toContain("생존")
        expect(describeGridLabel(playing)).toContain("생존 2명")
        expect(describeGridLabel(playing)).toContain(`${DODGE_RULES.cols}×${DODGE_RULES.rows}`)
    })

    it("draws hidden stack mates as numbers, which touch devices can actually see", () => {
        // title 속성은 hover 가 없는 기기에서 영영 보이지 않는다. 번호는 판 아래 대응표로
        // 이름까지 되짚을 수 있다.
        expect(describeStackBadge([])).toBe("")
        expect(describeStackBadge([5])).toBe("+5")
        expect(describeStackBadge([5, 7])).toBe("+5·7")
        expect(describeStackBadge([1, 2, 3])).toBe("+3명")
        expect(describeStackBadge([0])).toBe("+?")
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

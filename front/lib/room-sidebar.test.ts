import { describe, expect, it } from "vitest"
import { canStartRoom, isRoomHost, MIN_PLAYERS_TO_START } from "@/lib/room-sidebar"
import type { GameType, ParticipantView } from "@/lib/types"

function participant(id: string, ready: boolean): ParticipantView {
    return {
        participantId: id,
        displayName: id,
        kind: "MEMBER",
        ready,
        connection: "CONNECTED",
    }
}

// GAME-AC-29 — 시작 최소 인원은 게임 종류별이다. 서버(GameType.minPlayersToStart)와 같은
// 값을 쓰지 않으면 버튼은 비활성인데 서버는 허용하는(또는 그 반대의) 어긋난 화면이 된다.
describe("canStartRoom", () => {
    it("장애물피하기는 방장 혼자 READY 면 시작할 수 있다", () => {
        expect(canStartRoom([participant("host", true)], "DODGE")).toBe(true)
    })

    it("장애물피하기도 그 한 명이 READY 가 아니면 시작할 수 없다", () => {
        expect(canStartRoom([participant("host", false)], "DODGE")).toBe(false)
    })

    it("오목은 혼자서는 전원 READY 여도 시작할 수 없다", () => {
        expect(canStartRoom([participant("host", true)], "OMOK")).toBe(false)
    })

    it("오목은 2명 전원 READY 면 시작할 수 있다", () => {
        expect(canStartRoom([participant("host", true), participant("guest", true)], "OMOK")).toBe(true)
    })

    it("인원이 충분해도 한 명이라도 READY 가 아니면 시작할 수 없다", () => {
        const participants = [participant("host", true), participant("guest", false)]
        for (const gameType of ["OMOK", "DODGE"] as GameType[]) {
            expect(canStartRoom(participants, gameType)).toBe(false)
        }
    })

    it("빈 방은 어느 게임도 시작할 수 없다", () => {
        expect(canStartRoom([], "DODGE")).toBe(false)
        expect(canStartRoom([], "OMOK")).toBe(false)
    })
})

describe("MIN_PLAYERS_TO_START", () => {
    it("서버 GameType.minPlayersToStart 와 같은 값이다 — 오목 2, 장애물피하기 1", () => {
        expect(MIN_PLAYERS_TO_START.OMOK).toBe(2)
        expect(MIN_PLAYERS_TO_START.DODGE).toBe(1)
    })
})

describe("isRoomHost", () => {
    it("내 participantId 가 방장 id 와 같을 때만 참이다", () => {
        expect(isRoomHost("a", "a")).toBe(true)
        expect(isRoomHost("b", "a")).toBe(false)
    })

    it("내 participantId 를 아직 모르면(null) 방장이 아니다", () => {
        expect(isRoomHost(null, "a")).toBe(false)
    })
})

import { describe, expect, it } from "vitest"
import {
    chooseMemberId,
    describeSocketStatus,
    isSocketSettled,
    memberParticipantId,
    resolveSelfParticipantId,
} from "./game-room"
import type { ParticipantView } from "./types"

/**
 * 두 게임 화면이 함께 쓰는 방 판단. 특히 resolveSelfParticipantId 는 조용히 틀리면 화면 전체가
 * 남의 것이 되는 함수라, 리뷰에서 실제로 잡힌 "명단의 마지막이 나" 회귀를 여기서 막는다.
 */

function participant(id: string, name: string): ParticipantView {
    return {
        participantId: id,
        displayName: name,
        kind: id.startsWith("m:") ? "MEMBER" : "GUEST",
        ready: false,
        connection: "CONNECTED",
    }
}

const HOST = participant("m:7", "방장")
const GUEST = participant("g:abc", "손님")

describe("resolveSelfParticipantId", () => {
    it("builds the member id from memberId — never from the position in the list", () => {
        // 방장이 새로고침하면 명단은 [방장, 손님] 이다. "마지막이 나" 는 여기서 손님을 고른다.
        expect(
            resolveSelfParticipantId({
                memberId: 7,
                guestParticipantId: null,
                participants: [HOST, GUEST],
            })
        ).toBe("m:7")
    })

    it("prefers the stored guest identity over the member token, like decideJoinGate", () => {
        expect(
            resolveSelfParticipantId({
                memberId: 7,
                guestParticipantId: "g:abc",
                participants: [HOST, GUEST],
            })
        ).toBe("g:abc")
    })

    it("returns the candidate before the first ROOM_STATE arrives", () => {
        expect(
            resolveSelfParticipantId({ memberId: 7, guestParticipantId: null, participants: [] })
        ).toBe("m:7")
        expect(
            resolveSelfParticipantId({ memberId: null, guestParticipantId: null, participants: [] })
        ).toBeNull()
    })

    it("rescues a stale candidate when the room holds exactly one person", () => {
        expect(
            resolveSelfParticipantId({
                memberId: 999,
                guestParticipantId: null,
                participants: [HOST],
            })
        ).toBe("m:7")
    })

    it("does not guess when the candidate is missing from a crowded room", () => {
        expect(
            resolveSelfParticipantId({
                memberId: 999,
                guestParticipantId: null,
                participants: [HOST, GUEST],
            })
        ).toBe("m:999")
        expect(
            resolveSelfParticipantId({
                memberId: null,
                guestParticipantId: null,
                participants: [HOST, GUEST],
            })
        ).toBeNull()
    })
})

describe("socket status", () => {
    it("treats only the terminal statuses as settled", () => {
        expect(isSocketSettled("rejected")).toBe(true)
        expect(isSocketSettled("closed")).toBe(true)
        expect(isSocketSettled("connecting")).toBe(false)
        expect(isSocketSettled("open")).toBe(false)
        expect(isSocketSettled("joined")).toBe(false)
        expect(isSocketSettled("reconnecting")).toBe(false)
    })

    it("stays silent only once joined", () => {
        expect(describeSocketStatus("joined")).toBeNull()
        expect(describeSocketStatus("connecting")).toBeTruthy()
        expect(describeSocketStatus("open")).toBeTruthy()
        expect(describeSocketStatus("reconnecting")).toBeTruthy()
        expect(describeSocketStatus("closed")).toBeTruthy()
    })

    it("uses the server error code for a rejection and falls back when the frame never arrived", () => {
        const withCode = describeSocketStatus("rejected", "game_roomFull")
        const withoutCode = describeSocketStatus("rejected")

        expect(withCode).toBeTruthy()
        expect(withoutCode).toBe("방에 입장할 수 없습니다. 링크를 다시 확인해 주세요.")
        expect(withCode).not.toBe(withoutCode)
    })
})

describe("member identity", () => {
    // 서버 GameParticipant.member 와 같은 규칙. 기보 뷰어도 이 함수로 내 말을 찾으므로
    // 접두사를 여기서 바꾸면 플레이 화면과 다시보기가 함께 움직인다.
    it("builds the member participant id and nothing at all for a guest", () => {
        expect(memberParticipantId(7)).toBe("m:7")
        expect(memberParticipantId(null)).toBeNull()
    })

    /**
     * localStorage 의 authMemberId 는 우리가 적어 둔 값이고, `/api/game/me` 는 게임 서버가
     * 토큰에서 뽑아 준 값이다. 뒤엣것이 오면 그쪽을 쓴다 — 다만 그 호출은 실패할 수 있고
     * 게스트에게는 아예 부를 수 없으므로, 없을 때 신원을 통째로 잃으면 안 된다.
     */
    it("prefers the server's answer but never loses the stored one", () => {
        expect(chooseMemberId(11, 7)).toBe(11)
        expect(chooseMemberId(null, 7)).toBe(7)
        expect(chooseMemberId(11, null)).toBe(11)
        expect(chooseMemberId(null, null)).toBeNull()
    })
})

"use client"

import { useEffect, useState } from "react"
import { gameAPI } from "@/lib/api"
import { chooseMemberId } from "@/lib/game-room"

/**
 * 게임 화면이 쓰는 내 memberId.
 *
 * <p>`useAuth().memberId` 는 로그인할 때 우리가 `localStorage.authMemberId` 에 적어 둔
 * 값이다. `GET /api/game/me` 는 게임 서버가 액세스 토큰에서 뽑아 준 값이고, 그 검증기는
 * 소켓 JOIN 을 통과시키는 것과 같은 것이다 — 그래서 이쪽이 구조적으로 정확하다. 어긋난
 * 신원으로 플레이하면 남의 말이 내 말로 표시되고 판이 엉뚱한 차례에 잠긴다.
 *
 * <p>둘 중 무엇을 믿을지는 `chooseMemberId` 가 정한다(테스트가 고정한다). 이 훅은 언제
 * 부를지만 안다:
 * <ul>
 *   <li>로그인하지 않았으면 부르지 않는다. 게스트에게는 401 이 아니라 서버 예외다
 *       (`GamePrincipals.require` 가 던진다) — 부를 이유도 없다.</li>
 *   <li>실패해도 조용히 저장값으로 돌아간다. 게임 서버 HTTP 가 죽어 있어도 웹소켓은
 *       살아 있을 수 있고, 그때 화면 전체를 못 쓰게 만들 이유가 없다.</li>
 * </ul>
 */
export function useVerifiedMemberId(storedMemberId: number | null): number | null {
    const [verified, setVerified] = useState<number | null>(null)

    useEffect(() => {
        // 저장된 신원이 바뀌면 확인값을 **먼저 버린다**. chooseMemberId 는 확인값을
        // 우선하므로, 계정이 바뀐 뒤에도 옛 확인값이 남아 있으면 그때만은 이 훅이 틀린
        // 신원을 돌려준다 — 되돌아갈 값이 없어 신원을 잃는 것과 달리, 그건 남의 판을
        // 내 것으로 조작하게 만드는 실패다.
        setVerified(null)

        if (storedMemberId === null) {
            return
        }

        let active = true
        gameAPI
            .me()
            .then((principal) => {
                if (active && typeof principal.memberId === "number") {
                    setVerified(principal.memberId)
                }
            })
            .catch((error) => {
                console.warn("Failed to verify member id from /api/game/me", error)
            })

        return () => {
            active = false
        }
    }, [storedMemberId])

    return chooseMemberId(verified, storedMemberId)
}

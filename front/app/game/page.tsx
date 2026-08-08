import { redirect } from "next/navigation"

// 게임 허브가 홈(`/`)으로 올라갔다. 이 경로를 지우지 않고 리다이렉트로 남기는 이유는
// 기존 북마크와 화면 곳곳의 "게임 목록으로" 링크(/game)를 한꺼번에 깨지 않기 위해서다.
// 초대 링크(/game/omok/[roomId] 등)는 하위 세그먼트라 이 페이지를 거치지 않는다.
export default function GameHubRedirectPage() {
    redirect("/")
}

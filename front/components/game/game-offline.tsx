import Link from "next/link"
import { Button } from "@/components/ui/button"

/**
 * 게임 서버(app-webflux)를 띄우지 않을 때 게임 화면 자리에 서는 안내.
 *
 * <p>화면을 지우지 않고 이걸 세우는 이유: 라우트를 지우면 404 가 나서 사이트가 고장 난 것처럼
 * 보이고, 반대로 화면을 그대로 두면 소켓이 조용히 실패해 「연결 중…」에서 영영 멈춘다. 둘 다
 * 원인을 말해 주지 않는다. 여기서는 닫혀 있다는 사실을 그대로 말한다.
 *
 * @see gameEnabled (front/lib/game-config.ts)
 */
export default function GameOffline() {
    return (
        <div className="mx-auto flex min-h-[60vh] max-w-md flex-col items-center justify-center gap-4 px-4 text-center">
            <h1 className="text-xl font-semibold">게임은 지금 닫혀 있습니다</h1>
            <p className="text-sm leading-relaxed text-muted-foreground">
                오목과 장애물 피하기를 돌리던 게임 서버를 내려 두었습니다. 다시 열면 이 자리에서
                그대로 이어집니다.
            </p>
            <Button asChild variant="outline">
                <Link href="/">공동구매로 가기</Link>
            </Button>
        </div>
    )
}

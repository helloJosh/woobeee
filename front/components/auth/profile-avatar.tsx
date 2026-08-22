"use client"

import { UserRound } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * 프로필 아바타 한 개. 헤더와 마이페이지가 같은 모양을 쓰도록 여기 모은다.
 *
 * `src` 가 없으면 회색 원 + 사람 아이콘이다. `<img>` 를 쓰고 `next/image` 를 쓰지 않는 것은
 * 소스가 blob URL 이기 때문이다 — 최적화기가 손댈 수 있는 원격 주소가 아니다.
 */
export default function ProfileAvatar({
    src,
    sizeClassName = "h-8 w-8",
    iconClassName = "h-4 w-4",
    className,
}: {
    src: string | null
    sizeClassName?: string
    iconClassName?: string
    className?: string
}) {
    return (
        <span
            className={cn(
                "relative flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted",
                sizeClassName,
                className,
            )}
        >
            {src ? (
                <img src={src} alt="" className="h-full w-full object-cover" />
            ) : (
                <UserRound aria-hidden className={cn("text-muted-foreground", iconClassName)} />
            )}
        </span>
    )
}

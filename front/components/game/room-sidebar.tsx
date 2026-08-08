"use client"

import { useEffect, useRef, useState } from "react"
import { Check, Copy, Crown, WifiOff } from "lucide-react"
import { Button } from "@/components/ui/button"
import { canStartRoom, copyTextToClipboard, isRoomHost } from "@/lib/room-sidebar"
import type { GameType, ParticipantView, RoomStatus } from "@/lib/types"

interface RoomSidebarProps {
    gameType: GameType
    participants: ParticipantView[]
    hostParticipantId: string
    status: RoomStatus
    inviteUrl: string
    selfParticipantId: string | null
    onReadyToggle: (ready: boolean) => void
    onStart: () => void
    onRematch: () => void
}

export default function RoomSidebar({
    gameType,
    participants,
    hostParticipantId,
    status,
    inviteUrl,
    selfParticipantId,
    onReadyToggle,
    onStart,
    onRematch,
}: RoomSidebarProps) {
    const [copied, setCopied] = useState(false)
    const [copyFailed, setCopyFailed] = useState(false)
    const copiedTimeoutRef = useRef<number | null>(null)

    useEffect(() => {
        return () => {
            if (copiedTimeoutRef.current !== null) {
                window.clearTimeout(copiedTimeoutRef.current)
            }
        }
    }, [])

    const self = participants.find((p) => p.participantId === selfParticipantId)
    const isHost = isRoomHost(selfParticipantId, hostParticipantId)
    const everyoneReady = canStartRoom(participants, gameType)

    const copyInvite = async () => {
        const success = await copyTextToClipboard(inviteUrl)
        // 두 경로가 모두 실패하면 아무 표시도 없는 것이 가장 나쁘다 — 위 입력칸에서
        // 직접 복사하면 된다고 알려준다.
        setCopyFailed(!success)
        if (!success) {
            return
        }
        setCopied(true)
        if (copiedTimeoutRef.current !== null) {
            window.clearTimeout(copiedTimeoutRef.current)
        }
        copiedTimeoutRef.current = window.setTimeout(() => setCopied(false), 1500)
    }

    return (
        <aside className="flex w-full shrink-0 flex-col gap-6 lg:w-72">
            <div className="space-y-2">
                <h2 className="text-sm font-medium">초대 링크</h2>
                <div className="flex gap-2">
                    <input
                        readOnly
                        value={inviteUrl}
                        className="min-w-0 flex-1 truncate rounded-md border bg-muted px-3 py-2 text-xs"
                    />
                    <Button size="icon" variant="outline" onClick={copyInvite} aria-label="초대 링크 복사">
                        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                    </Button>
                </div>
                {copyFailed ? (
                    <p className="text-xs text-destructive">
                        자동 복사에 실패했습니다. 위 주소를 직접 선택해 복사해 주세요.
                    </p>
                ) : null}
            </div>

            <div className="space-y-2">
                <h2 className="text-sm font-medium">참가자 ({participants.length})</h2>
                {participants.length === 0 ? (
                    <p className="rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
                        아직 참가자가 없습니다.
                    </p>
                ) : (
                    <ul className="space-y-1">
                        {participants.map((participant) => (
                            <li
                                key={participant.participantId}
                                className="flex items-center justify-between rounded-md border px-3 py-2 text-sm"
                            >
                                <span className="flex min-w-0 items-center gap-1.5">
                                    {participant.participantId === hostParticipantId ? (
                                        <Crown className="h-3.5 w-3.5 shrink-0 text-amber-500" />
                                    ) : null}
                                    <span className="truncate">{participant.displayName}</span>
                                    {participant.connection === "DISCONNECTED" ? (
                                        <WifiOff className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                                    ) : null}
                                </span>
                                {status === "WAITING" ? (
                                    <span
                                        className={
                                            participant.ready
                                                ? "text-xs text-emerald-600"
                                                : "text-xs text-muted-foreground"
                                        }
                                    >
                                        {participant.ready ? "준비" : "대기"}
                                    </span>
                                ) : null}
                            </li>
                        ))}
                    </ul>
                )}
            </div>

            {status === "WAITING" ? (
                <div className="space-y-2">
                    <Button
                        variant={self?.ready ? "outline" : "default"}
                        className="w-full"
                        disabled={!self}
                        onClick={() => onReadyToggle(!self?.ready)}
                    >
                        {self?.ready ? "준비 해제" : "준비"}
                    </Button>
                    {isHost ? (
                        <Button className="w-full" disabled={!everyoneReady} onClick={onStart}>
                            게임 시작
                        </Button>
                    ) : null}
                </div>
            ) : null}

            {status === "FINISHED" ? (
                // 재대국(GAME-AC-30). 아무 멤버나 누를 수 있다 — 방은 WAITING 으로 돌아가고
                // 전원이 다시 READY 를 눌러야 시작되므로 방장으로 좁힐 이유가 없다.
                <Button className="w-full" disabled={!self} onClick={onRematch}>
                    다시하기
                </Button>
            ) : null}
        </aside>
    )
}

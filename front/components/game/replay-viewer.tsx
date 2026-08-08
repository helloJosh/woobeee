"use client"

import { useEffect, useState } from "react"
import { Loader2, Pause, Play, SkipBack, SkipForward } from "lucide-react"
import { Button } from "@/components/ui/button"
import DodgeGrid, { DODGE_PLAYER_COLORS } from "@/components/game/dodge-grid"
import OmokBoard from "@/components/game/omok-board"
import {
    buildReplayView,
    clampReplayIndex,
    describeReplayFailure,
    describeReplayFrameEvent,
    describeReplayHttpError,
    describeReplayLabel,
    describeReplayPlayerName,
    describeReplayPosition,
    maxReplayIndex,
    replayStepDelayMs,
    type ReplayView,
} from "@/lib/replay-view"
import type { GameType } from "@/lib/types"

interface ReplayViewerProps {
    gameType: GameType
    /** presigned 다운로드 URL. `gameAPI.replayUrl` 이 방금 발급받은 것. */
    replayUrl: string
    /** 내 participantId(`m:<memberId>`). 재생에서 내 말을 표시하는 데만 쓴다. */
    selfParticipantId: string | null
    onClose: () => void
}

/**
 * 기보 다시보기. 판단은 전부 lib/replay-view.ts 에 있고 여기서는 그리기만 한다 —
 * 두 기보 형식의 해석, 재생 위치의 의미(오목은 수, 장애물피하기는 틱), 실패 문구가 모두
 * 그쪽에 있고 테스트가 고정한다.
 */
export default function ReplayViewer({
    gameType,
    replayUrl,
    selfParticipantId,
    onClose,
}: ReplayViewerProps) {
    const [view, setView] = useState<ReplayView | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [index, setIndex] = useState(0)
    const [playing, setPlaying] = useState(false)

    useEffect(() => {
        // 이전 URL 의 응답이 늦게 도착해 새 기보 위를 덮어쓰는 것을 막는다.
        let active = true
        setView(null)
        setError(null)
        setIndex(0)
        setPlaying(false)

        // presigned URL 은 MinIO 를 직접 가리킨다(Next rewrites 를 타지 않는다).
        // 서명이 곧 인증이므로 쿠키를 붙이지 않는다 — 붙이면 오히려 CORS 가 더 까다로워진다.
        fetch(replayUrl, { credentials: "omit" })
            .then(async (response) => {
                if (!active) {
                    return
                }
                if (!response.ok) {
                    setError(describeReplayHttpError(response.status))
                    return
                }
                const text = await response.text()
                if (!active) {
                    return
                }
                setView(buildReplayView(gameType, text, selfParticipantId))
            })
            .catch((cause) => {
                if (!active) {
                    return
                }
                // 던져진 문장은 영어 진단 메시지다. 화면에는 올리지 않고 콘솔로만 보낸다.
                console.error("Replay could not be loaded", cause)
                setError(describeReplayFailure(cause))
            })

        return () => {
            active = false
        }
    }, [replayUrl, gameType, selfParticipantId])

    const lastIndex = view ? maxReplayIndex(view) : 0

    useEffect(() => {
        if (!view || !playing) {
            return
        }
        if (index >= lastIndex) {
            // 끝에 닿으면 스스로 멈춘다. 그러지 않으면 아무것도 진행되지 않는데 버튼만
            // 계속 "일시정지" 로 남는다.
            setPlaying(false)
            return
        }
        const timer = window.setTimeout(() => setIndex((current) => current + 1), replayStepDelayMs(view))
        return () => window.clearTimeout(timer)
    }, [view, playing, index, lastIndex])

    if (error) {
        return (
            <div className="space-y-3 rounded-lg border p-6">
                <p className="text-sm text-destructive">{error}</p>
                <Button variant="outline" onClick={onClose}>
                    닫기
                </Button>
            </div>
        )
    }

    if (!view) {
        return (
            <div className="flex items-center justify-center rounded-lg border p-10">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                <span className="sr-only">기보를 불러오는 중입니다</span>
            </div>
        )
    }

    const position = clampReplayIndex(view, index)
    const event = describeReplayFrameEvent(view, position)

    return (
        <div className="space-y-4 rounded-lg border p-4 sm:p-6">
            <div className="flex justify-center">
                {view.gameType === "OMOK" ? (
                    <OmokBoard
                        size={view.replay.boardSize}
                        placements={view.replay.placements.slice(0, position)}
                        disabled
                        onPlace={() => {}}
                    />
                ) : (
                    <DodgeGrid
                        players={view.frames[position]?.players ?? []}
                        obstacles={view.frames[position]?.obstacles ?? []}
                        label={describeReplayLabel(view, position)}
                    />
                )}
            </div>

            {/* 색·번호와 이름을 잇는 표. 판 위의 점만으로는 누가 누구인지 알 수 없다. */}
            {view.gameType === "OMOK" ? (
                <ul className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs">
                    {view.replay.players.map((player) => (
                        <li key={player.participantId} className="flex items-center gap-1.5">
                            <span
                                aria-hidden
                                className={
                                    player.color === "BLACK"
                                        ? "inline-block h-3 w-3 rounded-full bg-neutral-900"
                                        : "inline-block h-3 w-3 rounded-full border border-neutral-400 bg-white"
                                }
                            />
                            <span className="max-w-[10rem] truncate">
                                {describeReplayPlayerName(
                                    player.displayName,
                                    player.participantId === view.selfParticipantId
                                )}
                            </span>
                            <span className="text-muted-foreground">
                                {player.color === "BLACK" ? "흑" : "백"}
                            </span>
                        </li>
                    ))}
                </ul>
            ) : (
                <ul className="flex flex-wrap justify-center gap-x-3 gap-y-1 text-xs">
                    {view.roster.map((entry) => (
                        <li key={entry.participantId} className="flex items-center gap-1.5">
                            <span
                                aria-hidden
                                className={[
                                    "inline-flex h-4 w-4 items-center justify-center rounded-full",
                                    "text-[0.55rem] font-bold leading-none text-white",
                                    DODGE_PLAYER_COLORS[entry.colorIndex % DODGE_PLAYER_COLORS.length],
                                ].join(" ")}
                            >
                                {entry.playerNumber > 0 ? entry.playerNumber : "?"}
                            </span>
                            <span className="max-w-[8rem] truncate">
                                {describeReplayPlayerName(entry.displayName, entry.isSelf)}
                            </span>
                        </li>
                    ))}
                </ul>
            )}

            <div className="flex min-h-5 justify-center text-xs text-muted-foreground">{event}</div>

            <div className="space-y-3">
                <input
                    type="range"
                    min={0}
                    max={lastIndex}
                    step={1}
                    value={position}
                    aria-label="재생 위치"
                    className="w-full accent-foreground"
                    onChange={(changeEvent) => {
                        setPlaying(false)
                        setIndex(clampReplayIndex(view, Number(changeEvent.target.value)))
                    }}
                />

                <div className="flex flex-wrap items-center justify-center gap-2">
                    <Button size="icon" variant="outline" aria-label="처음으로" onClick={() => setIndex(0)}>
                        <SkipBack className="h-4 w-4" />
                    </Button>
                    <Button
                        size="icon"
                        variant="outline"
                        aria-label={playing ? "일시정지" : "재생"}
                        onClick={() => {
                            // 끝에서 재생을 누르면 처음부터. 그러지 않으면 아무 일도 일어나지 않는다.
                            if (!playing && position >= lastIndex) {
                                setIndex(0)
                            }
                            setPlaying((value) => !value)
                        }}
                    >
                        {playing ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                    </Button>
                    <Button
                        size="icon"
                        variant="outline"
                        aria-label="끝으로"
                        onClick={() => {
                            setPlaying(false)
                            setIndex(lastIndex)
                        }}
                    >
                        <SkipForward className="h-4 w-4" />
                    </Button>
                    <span className="ml-2 text-xs tabular-nums text-muted-foreground">
                        {describeReplayPosition(view, position)}
                    </span>
                    <Button variant="ghost" className="ml-2" onClick={onClose}>
                        닫기
                    </Button>
                </div>
            </div>
        </div>
    )
}

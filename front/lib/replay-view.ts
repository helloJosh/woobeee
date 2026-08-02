import {
    DODGE_RULES,
    REPLAY_MAX_TICKS,
    createDodgeGame,
    parseReplayNdjson,
    startingCells,
    stepReplay,
    type Cell,
} from "@/lib/dodge-engine"
import { colorIndexOf, playerNumberOf, type DodgeGridPlayer } from "@/lib/dodge-play"
import { NETWORK_ERROR_MESSAGE } from "@/lib/game-errors"
import { OMOK_BOARD_SIZE, type OmokPlacement, type OmokStone } from "@/lib/omok-play"
import type { GameResultSummary, GameType } from "@/lib/types"

/**
 * 마이페이지(전적 목록 + 기보 다시보기)의 판단 전부. omok-play.ts / dodge-play.ts 와 같은
 * 이유로 React 에 의존하지 않는다 — components/game/replay-viewer.tsx 와 app/mypage/page.tsx
 * 는 여기서 만든 것을 그리기만 한다.
 *
 * <p>기보는 두 종류다. **같은 파서를 공유하지 않는다.**
 * <ul>
 *   <li>오목: `OmokReplayWriter` 가 쓴 v1. 헤더 한 줄(players[]) + 착수 한 줄씩 `{t,p,x,y}`.
 *       실시간 화면의 GAME_SNAPSHOT 과는 다른 모양이라 그쪽 타입을 재사용하면 안 된다.</li>
 *   <li>장애물피하기: `DodgeReplayWriter` 가 쓴 v2. 입력과 <b>이탈</b>만 저장돼 있고 판은
 *       클라이언트가 같은 틱 로직으로 다시 돌려서 만든다 — 그 로직은 lib/dodge-engine.ts 에
 *       하나만 있고(`stepReplay`), 여기서 다시 적지 않는다.</li>
 * </ul>
 *
 * <p>둘 다 <b>못 읽으면 던진다</b>. 헤더 버전이나 규칙이 어긋나는 기보를 자기 상수로 억지로
 * 그리면 예외도 경고도 없이 "다른 게임"이 재생된다 — dodge-engine 의 parseReplayNdjson 이
 * 헤더 규칙을 대조하는 것과 같은 태도다.
 */

/** 오목 기보 헤더의 버전. `OmokReplayWriter.toNdjson` 의 `v` 와 같아야 한다. */
export const OMOK_REPLAY_VERSION = 1

/** 오목 재생의 한 수 간격(ms). 틱이 없는 게임이라 사람이 따라 읽을 수 있는 속도로 정한다. */
export const OMOK_REPLAY_STEP_MS = 500

export interface OmokReplayPlayer {
    participantId: string
    color: OmokStone
    displayName: string
}

export interface OmokReplay {
    boardSize: number
    players: OmokReplayPlayer[]
    /** 둔 순서 그대로. `placements.slice(0, index)` 가 곧 index 수까지의 판이다. */
    placements: OmokPlacement[]
}

function nonEmptyLines(text: string): string[] {
    return text
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
}

/**
 * 오목 기보(ndjson)를 판 위의 착수 목록으로 바꾼다.
 *
 * <p>색은 헤더의 players[] 에서만 온다. 착수 줄에는 참가자 식별자만 있고 색이 없다 —
 * 모르는 식별자를 만났을 때 흑으로 떨어뜨리면(그럴듯한 기본값이다) 판 전체가 조용히 틀린
 * 색으로 그려지고, 다시보기는 원본과 다른 대국이 된다. 그래서 던진다.
 */
export function parseOmokReplayNdjson(text: string): OmokReplay {
    const lines = nonEmptyLines(text)
    if (lines.length === 0) {
        throw new Error("Omok replay is empty")
    }

    const header = JSON.parse(lines[0])
    if (header.v !== OMOK_REPLAY_VERSION) {
        throw new Error(`Unsupported omok replay version ${header.v}; this reader needs v${OMOK_REPLAY_VERSION}`)
    }
    if (header.gameType !== "OMOK") {
        throw new Error(`Not an omok replay: gameType=${header.gameType}`)
    }
    // 판 크기가 다르면 좌표의 의미가 달라진다. 15줄 판에 19줄 기보를 그리면 오른쪽·아래
    // 착수가 사라지고 나머지는 제자리에 있는 것처럼 보인다 — 가장 알아채기 어려운 실패다.
    if (header.boardSize !== OMOK_BOARD_SIZE) {
        throw new Error(
            `Omok replay boardSize=${header.boardSize} does not match this client's ${OMOK_BOARD_SIZE}`
        )
    }

    const rawPlayers: unknown = header.players
    if (!Array.isArray(rawPlayers) || rawPlayers.length !== 2) {
        throw new Error("Omok replay header must carry exactly two players")
    }

    const players: OmokReplayPlayer[] = rawPlayers.map((player: any) => {
        if (player?.color !== "BLACK" && player?.color !== "WHITE") {
            throw new Error(`Unknown omok stone colour ${player?.color}`)
        }
        if (typeof player.participantId !== "string" || player.participantId.length === 0) {
            throw new Error("Omok replay player is missing participantId")
        }
        return {
            participantId: player.participantId,
            color: player.color,
            displayName:
                typeof player.displayName === "string" && player.displayName.length > 0
                    ? player.displayName
                    : player.participantId,
        }
    })
    if (players[0].color === players[1].color) {
        throw new Error("Omok replay players share the same stone colour")
    }

    const colorOf = new Map(players.map((player) => [player.participantId, player.color]))

    const placements: OmokPlacement[] = lines.slice(1).map((line) => {
        const move = JSON.parse(line)
        const color = colorOf.get(move.p)
        if (!color) {
            throw new Error(`Omok replay move belongs to an unknown participant ${move.p}`)
        }
        if (!isBoardCoordinate(move.x) || !isBoardCoordinate(move.y)) {
            throw new Error(`Omok replay move is off the board: (${move.x}, ${move.y})`)
        }
        return { x: move.x, y: move.y, color }
    })

    return { boardSize: OMOK_BOARD_SIZE, players, placements }
}

function isBoardCoordinate(value: unknown): value is number {
    return Number.isInteger(value) && (value as number) >= 0 && (value as number) < OMOK_BOARD_SIZE
}

export interface DodgeReplayFrame {
    /** 서버의 틱 번호. 0 은 첫 틱이 돌기 전, 시작 칸에 서 있는 상태다. */
    tick: number
    players: DodgeGridPlayer[]
    obstacles: Cell[]
    /** 이 틱에 맞아 사라진 사람들. 재생 중 "지금 누가 죽었는지" 를 말해 준다. */
    eliminatedThisTick: string[]
}

export interface DodgeReplayRosterEntry {
    participantId: string
    displayName: string
    colorIndex: number
    playerNumber: number
    isSelf: boolean
}

export interface DodgeReplayView {
    frames: DodgeReplayFrame[]
    roster: DodgeReplayRosterEntry[]
}

/**
 * 헤더의 표시 이름. `parseReplayNdjson` 은 participantId 만 돌려주므로(재생에 이름은 필요
 * 없다) 여기서 헤더 한 줄만 다시 읽는다 — dodge-engine 은 서버 DodgeGame 과 한 글자씩
 * 맞춰 둔 포트라 화면 편의를 위해 손대지 않는다.
 *
 * <p>규칙 검증은 하지 않는다. 이 함수를 부르기 전에 `parseReplayNdjson` 이 이미 헤더 전체를
 * 대조하고 통과시킨 뒤다.
 */
function readDodgeDisplayNames(text: string): Map<string, string> {
    const lines = nonEmptyLines(text)
    const header = JSON.parse(lines[0])
    const names = new Map<string, string>()
    for (const player of header.players ?? []) {
        if (typeof player?.participantId === "string" && typeof player?.displayName === "string") {
            names.set(player.participantId, player.displayName)
        }
    }
    return names
}

/**
 * 장애물피하기 기보를 프레임 목록으로 펼친다.
 *
 * <p>한 틱을 진행하는 코드는 여기 없다 — `stepReplay` 하나뿐이다. 화면용 재생과 테스트용
 * `rerunReplay` 가 각자 자기 스텝을 가지면, 이탈(v2 의 departures)처럼 나중에 추가된 규칙이
 * 한쪽에만 들어가고 <b>사람이 실제로 보는 화면</b>만 조용히 틀린 게임을 그리게 된다.
 *
 * <p>끝까지 가지 못한 기보는 던진다. 잘린 재생을 "짧은 게임" 으로 보여 주면 아무도 그것이
 * 잘렸다는 사실을 알 수 없다(서버 `DodgeReplayRunner` · `rerunReplay` 와 같은 태도).
 */
export function buildDodgeReplayView(text: string, selfParticipantId: string | null): DodgeReplayView {
    const replay = parseReplayNdjson(text)
    const names = readDodgeDisplayNames(text)
    // 색·번호 배정 순서는 헤더의 참가자 순서다. 시작 칸도 같은 순서로 배정되므로
    // (`startingCells`) 이 하나로 판과 명단이 같은 사람을 가리킨다.
    const order = replay.participantIds

    const toPlayers = (positions: Record<string, Cell>): DodgeGridPlayer[] =>
        Object.entries(positions).map(([participantId, cell]) => ({
            participantId,
            displayName: names.get(participantId) ?? participantId,
            x: cell.x,
            y: cell.y,
            colorIndex: colorIndexOf(order, participantId),
            playerNumber: playerNumberOf(order, participantId),
            isSelf: participantId === selfParticipantId,
        }))

    // 틱 0 — 아직 아무 틱도 돌지 않은 시작 판. createDodgeGame 이 내부 좌표를 노출하지 않으므로
    // 같은 함수(startingCells)로 만든다. 이 프레임이 없으면 재생이 첫 이동 뒤부터 시작한다.
    const startingPositions: Record<string, Cell> = {}
    startingCells(order.length).forEach((cell, index) => {
        startingPositions[order[index]] = cell
    })

    const frames: DodgeReplayFrame[] = [
        { tick: 0, players: toPlayers(startingPositions), obstacles: [], eliminatedThisTick: [] },
    ]

    const game = createDodgeGame(order, replay.seed)
    while (!game.finished && game.tick < REPLAY_MAX_TICKS) {
        const frame = stepReplay(game, replay)
        if (frame === null) {
            // 이탈이 그 틱에 게임을 끝냈다. 진행된 틱이 없으므로 프레임도 없다.
            break
        }
        frames.push({
            tick: frame.tick,
            players: toPlayers(frame.positions),
            obstacles: frame.obstacles,
            eliminatedThisTick: frame.eliminatedThisTick,
        })
    }

    if (!game.finished) {
        throw new Error(
            `Dodge replay for seed ${replay.seed} did not finish within ${REPLAY_MAX_TICKS} ticks`
        )
    }

    const roster: DodgeReplayRosterEntry[] = order.map((participantId) => ({
        participantId,
        displayName: names.get(participantId) ?? participantId,
        colorIndex: colorIndexOf(order, participantId),
        playerNumber: playerNumberOf(order, participantId),
        isSelf: participantId === selfParticipantId,
    }))

    return { frames, roster }
}

export type ReplayView =
    | { gameType: "OMOK"; replay: OmokReplay; selfParticipantId: string | null }
    | ({ gameType: "DODGE" } & DodgeReplayView)

/**
 * 기보 텍스트 한 덩어리를 화면이 그릴 수 있는 형태로. 게임 종류에 따라 파서가 완전히 갈린다.
 * 실패하면 던진다 — 호출자는 `describeReplayFailure` 로 문구를 만든다.
 */
export function buildReplayView(
    gameType: GameType,
    text: string,
    selfParticipantId: string | null
): ReplayView {
    if (gameType === "OMOK") {
        return { gameType: "OMOK", replay: parseOmokReplayNdjson(text), selfParticipantId }
    }
    return { gameType: "DODGE", ...buildDodgeReplayView(text, selfParticipantId) }
}

/**
 * 재생 위치의 최대값. 두 게임의 "한 걸음" 이 다르다.
 * <ul>
 *   <li>오목: 지금 판에 놓인 돌의 수. 0 이면 빈 판, 최대값이면 마지막 수까지 놓인 판.</li>
 *   <li>장애물피하기: 프레임 배열의 인덱스. 0 번 프레임이 시작 판이므로 최대값은 길이-1 이다.</li>
 * </ul>
 */
export function maxReplayIndex(view: ReplayView): number {
    return view.gameType === "OMOK" ? view.replay.placements.length : Math.max(view.frames.length - 1, 0)
}

export function clampReplayIndex(view: ReplayView, index: number): number {
    if (!Number.isFinite(index)) {
        return 0
    }
    return Math.min(Math.max(Math.round(index), 0), maxReplayIndex(view))
}

/** 자동 재생의 한 걸음 간격(ms). 장애물피하기는 서버 틱 간격 그대로 돌려야 원래 속도가 된다. */
export function replayStepDelayMs(view: ReplayView): number {
    return view.gameType === "OMOK" ? OMOK_REPLAY_STEP_MS : DODGE_RULES.tickMs
}

/** 컨트롤 옆에 적는 진행 표시. */
export function describeReplayPosition(view: ReplayView, index: number): string {
    const position = clampReplayIndex(view, index)
    const total = maxReplayIndex(view)
    return view.gameType === "OMOK" ? `${position} / ${total}수` : `${position} / ${total}틱`
}

/** 판(오목) · 격자(장애물피하기)의 접근성 이름. 그림만으로는 아무것도 읽히지 않는다. */
export function describeReplayLabel(view: ReplayView, index: number): string {
    const position = clampReplayIndex(view, index)
    if (view.gameType === "OMOK") {
        return `오목 기보 — ${position}수까지`
    }
    const frame = view.frames[position]
    const size = `장애물피하기 기보 ${DODGE_RULES.cols}×${DODGE_RULES.rows}`
    if (!frame) {
        return size
    }
    return `${size} — ${frame.tick}틱, 생존 ${frame.players.length}명, 장애물 ${frame.obstacles.length}개`
}

/**
 * 재생 중 지금 프레임에 대한 한 줄 설명. 장애물피하기는 탈락이 유일한 사건이라 그것만 말한다.
 */
export function describeReplayFrameEvent(view: ReplayView, index: number): string {
    if (view.gameType === "OMOK") {
        return ""
    }
    const frame = view.frames[clampReplayIndex(view, index)]
    if (!frame || frame.eliminatedThisTick.length === 0) {
        return ""
    }
    const nameOf = new Map(view.roster.map((entry) => [entry.participantId, entry.displayName]))
    const names = frame.eliminatedThisTick.map((id) => nameOf.get(id) ?? id)
    return `${names.join(", ")} 탈락`
}

/** 명단 한 줄. 내 말에는 표시를 붙인다 — 여덟 개 점 중 어느 것이 나인지 알 방법이 없다. */
export function describeReplayPlayerName(displayName: string, isSelf: boolean): string {
    return isSelf ? `${displayName} (나)` : displayName
}

/**
 * 기보 URL 이 200 이 아닐 때의 문구.
 *
 * <p>presigned URL 은 만료된다(`GameStorageProperties.presignedUrlExpirationSeconds`).
 * 오래 열어 둔 뒤 다시 재생을 누르면 403 이 오는데, 그건 사용자가 고칠 수 없는 것이 아니라
 * "다시 눌러 주세요" 로 해결되는 것이라 그렇게 말해 준다.
 */
export function describeReplayHttpError(status: number): string {
    if (status === 403 || status === 404 || status === 410) {
        return "기보 링크가 만료되었습니다. 다시 시도해 주세요."
    }
    return "기보를 내려받지 못했습니다. 잠시 후 다시 시도해 주세요."
}

/**
 * 내려받기·해석 실패의 문구.
 *
 * <p>파서가 던지는 문장은 영어 진단 메시지다(어느 헤더 필드가 어긋났는지). 그건 콘솔로
 * 보내고 화면에는 한국어 안내만 쓴다 — 서버의 영어 `message` 를 화면에 올리지 않는 것과
 * 같은 규칙이다.
 */
export function describeReplayFailure(error: unknown): string {
    // fetch 자체가 실패하면(백엔드 다운·오프라인·CORS) TypeError 다. describeGameApiError 와
    // 같은 판단이지만 이쪽은 apiRequest 를 거치지 않는 직접 fetch 라 여기서 다시 본다.
    if (error instanceof TypeError) {
        return NETWORK_ERROR_MESSAGE
    }
    return "기보를 재생할 수 없습니다. 파일 형식이 이 화면과 맞지 않습니다."
}

/** 전적 목록의 게임 이름. */
export function describeGameTypeName(gameType: GameType): string {
    return gameType === "OMOK" ? "오목" : "장애물피하기"
}

/**
 * 전적 한 줄의 제목. 등수는 서버가 `finish_rank` 로 준다(공동 순위가 있을 수 있다).
 */
export function describeResultTitle(result: GameResultSummary): string {
    return `${describeGameTypeName(result.gameType)} · ${result.finishRank}위`
}

/**
 * 전적 한 줄의 부제. 승자가 없으면 서버가 빈 문자열을 준다(COALESCE) — 그대로 쓰면
 * "승자 " 로 끝나는 문장이 된다.
 */
export function describeResultSubtitle(result: GameResultSummary): string {
    const winner = result.winnerDisplayName ? result.winnerDisplayName : "없음"
    return `${formatEndedAt(result.endedAt)} · 승자 ${winner}`
}

/**
 * `ended_at` 은 시간대 없는 TIMESTAMP(6) 를 `String.valueOf` 로 찍은 것이다
 * (`2026-08-01T12:34:56.789012`).
 *
 * <p><b>`new Date(...)` 로 파싱하지 않는다.</b> 시간대가 없는 문자열이라 브라우저가 그것을
 * 로컬 시각으로 읽고 다시 로컬로 찍으면 값은 같아 보이지만, 문자열 끝에 `Z` 가 붙는 순간
 * (서버 컬럼 타입이 바뀌기만 해도 그렇게 된다) 조용히 9시간 어긋난 시각을 보여 준다.
 * 여기서는 서버가 준 숫자를 읽기 좋게 자르기만 한다.
 */
export function formatEndedAt(raw: string): string {
    const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(raw ?? "")
    return match ? `${match[1]} ${match[2]}` : raw ?? ""
}

/**
 * 다음 쪽이 더 있는가. 마지막 요청이 limit 을 꽉 채워 왔다면 더 있을 수 있다는 뜻이다 —
 * 정확히 배수로 끝나는 경우 빈 쪽을 한 번 더 부르지만, 그것이 "더 보기" 버튼을 너무 일찍
 * 숨겨 남은 전적을 영영 못 보게 하는 것보다 낫다.
 */
export function hasMoreResults(lastPageSize: number, limit: number): boolean {
    return lastPageSize >= limit && limit > 0
}

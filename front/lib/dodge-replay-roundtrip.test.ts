import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { describe, expect, it } from "vitest"
import { createDodgeGame, parseReplayNdjson, stepReplay, type DodgeFrame } from "./dodge-engine"

/**
 * 왕복 테스트. 진짜 서버 작성기(`DodgeReplayWriter`)가 쓴 파일을 이 리더가 읽고, 이 엔진으로
 * 재생한 자취가 자바 엔진의 자취와 글자 하나까지 같은지를 본다.
 *
 * <p>이것이 없을 때의 상태가 문제였다: 자바 쪽에는 작성기 단위 검사가, 여기에는
 * `dodge-engine.test.ts` 의 자체 골든이 있었지만 그 골든은 `createDodgeGame` 을 직접 돌려
 * 만든 것이라 <b>작성기의 출력을 한 번도 통과하지 않았다</b>. 그래서 헤더 파싱이 조용히
 * 무너져도(예: seed 를 잃어 xorshift 가 0 으로 눕는 경우) 두 스위트가 모두 초록이었다.
 *
 * <p>픽스처 두 개는 `app-webflux/src/test/resources/replay/` 에 커밋돼 있다. 백엔드 트리를
 * 가리키는 것이 의도다 — <b>같은 파일</b>이어야 왕복이고, 복사본을 두면 그 순간부터 두 벌이
 * 따로 늙는다. 자바 쪽 `DodgeReplayWriterTest` 가 같은 파일에 대해 같은 주장을 한다.
 */
// 이 파일(front/lib/…)에서 레포 루트까지 두 칸이다.
const FIXTURE_DIR = "../../app-webflux/src/test/resources/replay/"

function fixture(name: string): string {
    return readFileSync(fileURLToPath(new URL(FIXTURE_DIR + name, import.meta.url)), "utf8")
}

/**
 * `ReplayFixture.traceOf` 와 <b>같은 줄 모양</b>을 만들어야 한다. 그쪽 자바독에 형식이 적혀
 * 있다: 진행된 틱마다 한 줄, 마지막에 요약 한 줄.
 */
function renderFrame(frame: DodgeFrame): string {
    const positions = Object.entries(frame.positions)
        .map(([participantId, cell]) => `${participantId}@${cell.x},${cell.y}`)
        .join("|")
    const obstacles = frame.obstacles.map((o) => `${o.x},${o.y},${o.w},${o.h}`).join("|")
    return (
        `t${frame.tick} pos=${positions} obs=${obstacles}` +
        ` elim=${frame.eliminatedThisTick.join("|")} fin=${frame.finished ? 1 : 0}`
    )
}

function traceOf(ndjson: string): string {
    const replay = parseReplayNdjson(ndjson)
    const game = createDodgeGame(replay.participantIds, replay.seed)
    const lines: string[] = []

    while (!game.finished && game.tick < 100_000) {
        const frame = stepReplay(game, replay)
        if (frame === null) {
            break
        }
        lines.push(renderFrame(frame))
    }

    const ranks = game.finalRanks()
    const rendered = replay.participantIds
        .map((participantId) => `${participantId}=${ranks[participantId]}`)
        .join("|")
    lines.push(`final ticks=${game.tick} ranks=${rendered}`)
    return lines.join("\n") + "\n"
}

describe("dodge replay round trip", () => {
    it("replays the committed writer output into the committed trace", () => {
        expect(traceOf(fixture("dodge-replay-v3.ndjson"))).toBe(fixture("dodge-replay-v3.trace.txt"))
    })

    /**
     * 픽스처가 실제로 흥미로운 파일인지. 이탈이 없거나 참가자가 하나뿐인 기보로 바뀌면 위
     * 비교는 여전히 통과하면서 검사 범위만 조용히 줄어든다 — 자바 쪽에도 같은 취지의 검사가
     * 있고, 두 곳 다 있어야 어느 쪽에서 다시 만들어도 걸린다.
     */
    it("keeps exercising a real multi-player game with a departure", () => {
        const replay = parseReplayNdjson(fixture("dodge-replay-v3.ndjson"))

        expect(replay.participantIds).toEqual(["m:11", "g:a", "g:b", "g:c"])
        expect(replay.seed).toBe(8412739)
        expect(Object.values(replay.departuresByTick).flat()).toContain("g:c")
        expect(Object.keys(replay.inputsByTick).length).toBeGreaterThan(3)
    })

    /**
     * 이 왕복이 잡아야 하는 실패의 모양. seed 를 잃으면(`undefined`) 예전 리더는 state 0 으로
     * 눕어 매 틱 모든 열에 장애물을 뿌리는 "그럴듯한 짧은 게임" 을 예외 없이 그렸다. 지금은
     * 던져야 한다 — 자취가 달라지는 것이 아니라 아예 읽히지 않아야 한다.
     */
    it("refuses the same fixture with its seed stripped instead of replaying a different game", () => {
        const lines = fixture("dodge-replay-v3.ndjson").trim().split("\n")
        const header = JSON.parse(lines[0])
        delete header.seed
        const stripped = [JSON.stringify(header), ...lines.slice(1)].join("\n") + "\n"

        expect(() => parseReplayNdjson(stripped)).toThrow(/seed/)
    })
})

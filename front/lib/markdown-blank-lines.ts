// 마크다운은 연속된 빈 줄을 문단 구분 하나로 접는다 — 에디터에서 Enter 를 여러 번 눌러
// 만든 세로 여백이 미리보기/게시 화면에서 사라진다. 여분의 빈 줄 하나당 보이지 않는
// 문단(&nbsp;)을 끼워 간격을 살린다. 코드 펜스 안의 빈 줄은 원문 그대로 보존해야 한다.

const FENCE = /^\s{0,3}(```|~~~)/

export const preserveBlankLines = (markdown: string): string => {
    const out: string[] = []
    let inFence = false
    let blankRun = 0

    const flushBlanks = () => {
        if (blankRun === 0) {
            return
        }
        out.push("")
        for (let extra = 1; extra < blankRun; extra++) {
            out.push("&nbsp;", "")
        }
        blankRun = 0
    }

    for (const line of markdown.split("\n")) {
        if (inFence) {
            out.push(line)
            if (FENCE.test(line)) {
                inFence = false
            }
            continue
        }
        if (FENCE.test(line)) {
            flushBlanks()
            out.push(line)
            inFence = true
            continue
        }
        if (line.trim() === "") {
            blankRun++
            continue
        }
        flushBlanks()
        out.push(line)
    }
    flushBlanks()

    return out.join("\n")
}

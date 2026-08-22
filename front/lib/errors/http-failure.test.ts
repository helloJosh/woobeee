import { describe, expect, it } from "vitest"

import { describeHttpFailure } from "./http-failure"

describe("describeHttpFailure", () => {
    /**
     * 413 은 컨테이너(Tomcat)가 multipart 를 파싱하다 상한을 넘겨 끊는 응답이다. 본문이
     * 비어 있어서 `ApiResponse` 봉투도, 오류 코드도 없다 — 그래서 일반 실패 문구로 뭉개지고
     * 사용자는 "왜 실패했는지"를 알 수 없었다. 크기 문제라고 말해 준다.
     */
    it("413 은 크기 문제라고 알려준다", () => {
        expect(describeHttpFailure(413)).toBe(
            "파일이 너무 큽니다. 서버가 허용하는 크기를 넘었습니다 — 더 작은 이미지로 다시 시도해 주세요.",
        )
    })

    /** 그 외 상태는 호출부의 기존 처리에 맡긴다 — 여기서 문구를 만들면 오류 코드 지도를 가린다. */
    it("다른 상태는 null 이다", () => {
        for (const status of [200, 400, 401, 403, 404, 409, 500, 502]) {
            expect(describeHttpFailure(status)).toBeNull()
        }
    })
})

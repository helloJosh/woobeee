import { describe, expect, it } from "vitest"

import {
    MAX_PROFILE_IMAGE_BYTES,
    PROFILE_IMAGE_ACCEPT,
    describeProfileImageError,
    pickImageFile,
    validateProfileImageFile,
} from "./profile-image"

const file = (type: string, size: number, name = "avatar.png") => ({ type, size, name })

describe("validateProfileImageFile", () => {
    it("허용 목록의 타입을 통과시킨다 — 서버 화이트리스트와 같은 넷이다", () => {
        for (const type of ["image/png", "image/jpeg", "image/webp", "image/gif"]) {
            expect(validateProfileImageFile(file(type, 1024))).toEqual({ ok: true })
        }
    })

    it("허용 목록 밖 타입을 거절한다 — svg 는 스크립트를 품을 수 있어 서버도 막는다", () => {
        const result = validateProfileImageFile(file("image/svg+xml", 1024))

        expect(result.ok).toBe(false)
        expect(result.ok === false && result.reason).toContain("png")
    })

    it("이미지가 아닌 파일을 거절한다", () => {
        expect(validateProfileImageFile(file("application/pdf", 1024, "doc.pdf")).ok).toBe(false)
    })

    it("정확히 상한인 파일은 통과시킨다 — 경계는 포함이다", () => {
        expect(validateProfileImageFile(file("image/png", MAX_PROFILE_IMAGE_BYTES))).toEqual({ ok: true })
    })

    it("상한을 1바이트 넘으면 거절한다 — 서버도 같은 경계로 400 을 낸다", () => {
        const result = validateProfileImageFile(file("image/png", MAX_PROFILE_IMAGE_BYTES + 1))

        expect(result.ok).toBe(false)
        expect(result.ok === false && result.reason).toContain("5MB")
    })

    it("빈 파일을 거절한다 — 0바이트를 올리면 조회가 깨진 이미지가 된다", () => {
        expect(validateProfileImageFile(file("image/png", 0)).ok).toBe(false)
    })

    it("타입 대문자·공백을 정규화해서 본다 — 브라우저가 보내는 값이 일정하지 않다", () => {
        expect(validateProfileImageFile(file(" IMAGE/PNG ", 1024))).toEqual({ ok: true })
    })
})

describe("pickImageFile", () => {
    it("드롭된 것 중 첫 이미지를 고른다", () => {
        const png = file("image/png", 10, "a.png")

        expect(pickImageFile([file("text/plain", 10, "note.txt"), png])).toBe(png)
    })

    it("이미지가 하나도 없으면 null 이다", () => {
        expect(pickImageFile([file("text/plain", 10, "note.txt")])).toBeNull()
    })

    it("빈 목록이면 null 이다 — 드롭 이벤트에 파일이 없을 수 있다", () => {
        expect(pickImageFile([])).toBeNull()
    })

    /**
     * 허용 목록 밖 이미지도 고른다. 고르는 것과 검증하는 것은 다른 일이다 — 여기서 걸러 버리면
     * svg 를 떨어뜨린 사용자에게 아무 반응이 없어 드롭이 먹지 않은 것처럼 보인다.
     * validateProfileImageFile 이 이유를 붙여 거절해야 한다.
     */
    it("이미지이기만 하면 고른다 — 거절 이유는 검증이 말한다", () => {
        const svg = file("image/svg+xml", 10, "a.svg")

        expect(pickImageFile([svg])).toBe(svg)
    })
})

describe("PROFILE_IMAGE_ACCEPT", () => {
    it("허용 타입을 input accept 값으로 낸다 — 파일 선택창이 애초에 다른 걸 못 고르게 한다", () => {
        expect(PROFILE_IMAGE_ACCEPT).toBe("image/png,image/jpeg,image/webp,image/gif")
    })
})

describe("describeProfileImageError", () => {
    it("Error 의 메시지를 그대로 쓴다 — 서버가 준 이유를 삼키지 않는다", () => {
        expect(describeProfileImageError(new Error("Profile image must be 5MB or smaller"))).toBe(
            "Profile image must be 5MB or smaller",
        )
    })

    it("메시지가 없으면 기본 문구를 쓴다", () => {
        expect(describeProfileImageError(new Error(""))).toBe("프로필 이미지를 저장하지 못했습니다.")
        expect(describeProfileImageError(null)).toBe("프로필 이미지를 저장하지 못했습니다.")
        expect(describeProfileImageError("문자열 예외")).toBe("프로필 이미지를 저장하지 못했습니다.")
    })
})

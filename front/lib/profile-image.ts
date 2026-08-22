/**
 * 프로필 이미지 업로드의 판단 로직. React 를 쓰지 않아 단위 테스트로 고정된다 — 이 판단이
 * 컴포넌트로 넘어가면 검증 밖으로 나간다(`docs/FRONTEND.md`).
 *
 * 허용 타입과 크기 상한은 서버(`MemberProfileImageService`)와 같은 값이다. 프론트에서 먼저
 * 걸러 왕복을 줄이는 것이고, 서버 검증을 대신하는 것이 아니다.
 */

/** 서버 `ALLOWED_CONTENT_TYPES` 와 같은 넷. svg 는 스크립트를 품을 수 있어 빠져 있다. */
const ALLOWED_CONTENT_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"] as const

/** 서버 `MAX_PROFILE_IMAGE_BYTES` 와 같은 값. 업로드가 앱을 거치므로 상한이 필요하다. */
export const MAX_PROFILE_IMAGE_BYTES = 5 * 1024 * 1024

/** `<input type="file" accept>` 값. 파일 선택창이 애초에 다른 걸 못 고르게 한다. */
export const PROFILE_IMAGE_ACCEPT = ALLOWED_CONTENT_TYPES.join(",")

/** File 의 필요한 부분만 본다 — 테스트가 DOM 없이 돌게 하려는 것이다. */
export interface ImageFileLike {
    type: string
    size: number
}

export type ProfileImageValidation = { ok: true } | { ok: false; reason: string }

const normalizeType = (type: string) => type.trim().toLowerCase()

export const validateProfileImageFile = (file: ImageFileLike): ProfileImageValidation => {
    if (!ALLOWED_CONTENT_TYPES.includes(normalizeType(file.type) as (typeof ALLOWED_CONTENT_TYPES)[number])) {
        return { ok: false, reason: "png, jpeg, webp, gif 이미지만 올릴 수 있습니다." }
    }

    if (file.size <= 0) {
        return { ok: false, reason: "빈 파일은 올릴 수 없습니다." }
    }

    if (file.size > MAX_PROFILE_IMAGE_BYTES) {
        return { ok: false, reason: "이미지는 5MB 이하여야 합니다." }
    }

    return { ok: true }
}

/**
 * 드롭된 것 중 첫 이미지를 고른다.
 *
 * 허용 목록으로 좁히지 않는 것이 의도다 — 여기서 걸러 버리면 svg 를 떨어뜨린 사용자에게 아무
 * 반응이 없어 드롭이 먹지 않은 것처럼 보인다. 거절 이유는 {@link validateProfileImageFile} 이
 * 말해야 한다.
 */
export const pickImageFile = <T extends ImageFileLike>(files: readonly T[]): T | null =>
    files.find((file) => normalizeType(file.type).startsWith("image/")) ?? null

export const describeProfileImageError = (cause: unknown): string => {
    if (cause instanceof Error && cause.message.trim()) {
        return cause.message
    }
    return "프로필 이미지를 저장하지 못했습니다."
}

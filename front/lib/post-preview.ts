// front/lib/post-preview.ts — 홈 목록의 요약. 글에 전용 필드가 없어 본문 마크다운에서 만든다.
// React-free: 컴포넌트는 값을 그리기만 한다.

export const MAX_DESCRIPTION_LENGTH = 300

/** BLOG-AC-23 — 제목 아래 줄: 작성자가 적은 설명이 있으면 그것, 없으면 본문 요약. */
export function summaryOf(description: string | null | undefined, markdown: string | null | undefined, maxChars: number): string {
    const d = description?.trim()
    return d ? d : excerpt(markdown, maxChars)
}

/**
 * 마크다운 기호를 걷어낸 본문 앞부분. 헤딩 줄과 코드블록은 통째로 빠지고, 링크·강조·인용·인라인 코드는
 * 텍스트만 남는다. 공백은 한 칸으로 접고 maxChars 를 넘으면 "…" 을 붙인다.
 */
export function excerpt(markdown: string | null | undefined, maxChars: number): string {
    if (!markdown) return ""
    let text = markdown
        .replace(/```[\s\S]*?```/g, " ")            // 펜스 코드블록
        .replace(/~~~[\s\S]*?~~~/g, " ")
        .replace(/^\s{0,3}#{1,6}\s.*$/gm, " ")       // 헤딩 줄
        .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")       // 이미지
        .replace(/<[^>]+>/g, " ")                    // HTML 태그
        .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")     // 링크 → 텍스트
        .replace(/^\s{0,3}>\s?/gm, "")               // 인용 표식
        .replace(/^\s{0,3}([-*+]|\d+\.)\s+/gm, "")   // 목록 표식
        .replace(/`([^`]*)`/g, "$1")                 // 인라인 코드
        .replace(/(\*\*|__)(.*?)\1/g, "$2")          // 굵게
        .replace(/(\*|_)(.*?)\1/g, "$2")             // 기울임
        .replace(/~~(.*?)~~/g, "$1")                 // 취소선
        .replace(/^\s{0,3}([-*_])\s*(\1\s*){2,}$/gm, " ") // 수평선
        .replace(/\s+/g, " ")
        .trim()
    if (text.length > maxChars) text = text.slice(0, maxChars) + "…"
    return text
}

import { common, createLowlight } from "lowlight"

/**
 * 본문 마크다운의 ```<언어> 펜스에 색을 넣는 쪽의 판단을 모아 둔다.
 *
 * 실제 하이라이팅은 `rehype-highlight` 가 lowlight 의 `common` 문법 집합(37개)으로 한다.
 * 쓰지도 않는 문법이 실린다는 게 걸려서 실제로 쓰는 14개만 개별 등록해 재 봤는데, 오히려
 * 글 상세 라우트가 54.3kB → 64.2kB 로 **늘었다** -- `common` 은 한 덩어리로 묶여 있어
 * 번들러가 더 잘 다루고, 에디터(BlockNote)가 쓰는 lowlight 와도 공유된다. 그래서 기본값을
 * 그대로 쓴다.
 *
 * 이 모듈이 있는 이유는 그 집합이 **조용히** 실패하기 때문이다 -- 집합에 없는 언어를 만나면
 * rehype-highlight 는 예외를 던지지 않고 색 없는 평문으로 렌더한다. 화면에서만 알 수 있고,
 * 그마저도 "원래 그런 색인가" 싶어 지나치기 쉽다. 그래서 우리가 실제로 쓰는 언어를 아래에
 * 적어 두고 테스트로 고정한다.
 */
/**
 * 글에서 쓰는 펜스 언어. 별칭(`ts`, `js`, `yml`, `sh`)도 함께 넣는다 -- lowlight 는 별칭을
 * 문법 정의 안에서 풀기 때문에 `common` 의 키 목록에는 나타나지 않는다.
 * 키만 대조하면 멀쩡한 별칭을 빠졌다고 잡으므로, 확인은 별칭까지 보는 `registered` 로 한다.
 */
export const EXPECTED_FENCE_LANGUAGES = [
    "sql",
    "java",
    "kotlin",
    "typescript",
    "ts",
    "javascript",
    "js",
    "json",
    "yaml",
    "yml",
    "bash",
    "sh",
    "shell",
    "xml",
    "html",
    "python",
    "go",
    "diff",
    "plaintext",
] as const

const lowlight = createLowlight(common)

/** 이 언어로 펜스를 열면 색이 들어가는가. 별칭도 참으로 본다. */
export function isHighlightable(language: string): boolean {
    return lowlight.registered(language.trim().toLowerCase())
}

/**
 * {@link EXPECTED_FENCE_LANGUAGES} 중 하이라이팅되지 않는 것들. 비어 있어야 정상이다.
 * lowlight 를 올리거나 문법 집합이 좁아졌을 때 무엇이 빠졌는지 이름으로 알려 준다.
 */
export function missingLanguages(
    expected: readonly string[] = EXPECTED_FENCE_LANGUAGES,
): string[] {
    return expected.filter((language) => !isHighlightable(language))
}

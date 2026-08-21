import { defaultUrlTransform } from "react-markdown"

/**
 * react-markdown 의 기본 sanitizer 는 https?/mailto 등 몇 개 프로토콜만 통과시키고
 * 나머지는 빈 문자열로 바꾼다 — 편집기 미리보기의 드롭 이미지(blob URL)가 여기 걸려
 * 깨진 이미지가 된다. blob: 은 같은 오리진에서 만든 객체 URL 이라 javascript: 류의
 * 실행 위험이 없으므로 그것만 추가로 허용한다.
 */
export const markdownUrlTransform = (url: string): string =>
    url.startsWith("blob:") ? url : defaultUrlTransform(url)

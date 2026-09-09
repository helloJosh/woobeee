import { firstImageUrl, placeholderHue } from "@/lib/post-preview"

/**
 * 카드·히어로 썸네일. 본문 첫 이미지가 있으면 그것, 없으면 카테고리 이름이 박힌 그라데이션 타일.
 * next/image 를 쓰지 않는 이유: 이미지가 presigned URL(외부 호스트)이라 도메인 허용 목록이 필요하고,
 * 목록 카드에서는 그 최적화가 값어치보다 설정 부담이 크다.
 */
export default function PostThumbnail({ content, categoryName, className = "" }: {
    content: string
    categoryName: string
    className?: string
}) {
    const url = firstImageUrl(content)
    if (url) {
        // eslint-disable-next-line @next/next/no-img-element
        return <img src={url} alt="" loading="lazy" className={`h-full w-full object-cover ${className}`} />
    }
    const hue = placeholderHue(categoryName)
    return (
        <div
            aria-hidden
            className={`flex h-full w-full items-end p-4 ${className}`}
            style={{ background: `linear-gradient(135deg, hsl(${hue} 70% 45%), hsl(${(hue + 40) % 360} 65% 30%))` }}
        >
            <span className="text-lg font-bold tracking-tight text-white/90 drop-shadow">{categoryName}</span>
        </div>
    )
}

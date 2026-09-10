"use client"

import { useState } from "react"

/**
 * 헤더 아래 전체 폭 소개 배너 — 우아한 기술블로그의 민트 배너 자리. 클릭하면 이력서(새 탭).
 * 파일은 둘 다 front/public 에 둔다. 배너 이미지가 아직 없으면(404) 같은 문구의 CSS 배너로 대체한다.
 *   - 이미지: public/home-banner.png  (권장 1600×360 정도, 가로로 긴 그림)
 *   - 이력서: public/resume.pdf
 */
export const BANNER_IMAGE = "/home-banner.png"
export const RESUME_URL = "/resume.pdf"
const TITLE = "백엔드 개발자 김병우입니다"
const SUBTITLE = "만들고 배운 것을 기록하는 기술 블로그"

export default function HomeBanner() {
    const [imageMissing, setImageMissing] = useState(false)
    return (
        <a
            href={RESUME_URL}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`${TITLE} — 이력서 보기 (새 탭)`}
            title="클릭하면 이력서가 열립니다"
            className="group block w-full overflow-hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
            {imageMissing ? (
                <div className="flex h-44 w-full flex-col items-center justify-center gap-2 bg-teal-300 px-4 text-center text-neutral-900 transition-opacity group-hover:opacity-90 sm:h-56 dark:bg-teal-600 dark:text-white">
                    <span className="text-xs font-semibold tracking-[0.2em]">WOOBEEE</span>
                    <h2 className="text-3xl font-black tracking-tight sm:text-5xl">{TITLE}</h2>
                    <p className="text-sm sm:text-base">{SUBTITLE}</p>
                </div>
            ) : (
                // next/image 를 쓰지 않는 이유: 파일이 없을 때 onError 로 대체 배너를 그리는 단순한 폴백이 필요하다
                // eslint-disable-next-line @next/next/no-img-element
                <img
                    src={BANNER_IMAGE}
                    alt={`${TITLE} — ${SUBTITLE}`}
                    className="h-44 w-full object-cover transition-opacity group-hover:opacity-90 sm:h-56 lg:h-64"
                    onError={() => setImageMissing(true)}
                />
            )}
        </a>
    )
}

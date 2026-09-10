"use client"

import { useState } from "react"

/**
 * 헤더 아래 전체 폭 소개 배너 — 우아한 기술블로그의 민트 배너 자리. 장식용이고 클릭 동작은 없다
 * (이력서는 헤더의 RESUME 탭, components/resume-dialog.tsx).
 *   - 배너 이미지: public/home-banner-light.png / -dark.png (3840×766, scripts/render-banners.sh — 테마 팔레트에 맞춰 두 장,
 *     .dark 클래스로 CSS 가 골라 보인다. 문구·색을 바꾸면 scripts/home-banner-*.html 을 고치고 다시 찍는다)
 * 이미지가 없으면(404) 같은 문구의 CSS 배너(팔레트 토큰)로 대체해 화면이 깨지지 않는다.
 */
export const BANNER_IMAGE_LIGHT = "/home-banner-light.png"
export const BANNER_IMAGE_DARK = "/home-banner-dark.png"
const LABEL = "WOOBEEBLOG"
const TITLE = "백엔드엔지니어 김병우"
const SUBTITLE = "관심 있는 것을 공부합니다"

export default function HomeBanner() {
    const [imageMissing, setImageMissing] = useState(false)
    if (imageMissing) {
        return (
            <div className="flex h-44 w-full flex-col items-center justify-center gap-2 bg-primary px-4 text-center text-primary-foreground sm:h-56">
                <span className="text-xs font-bold tracking-[0.1em]">{LABEL}</span>
                <h2 className="text-3xl font-black tracking-tight sm:text-5xl">{TITLE}</h2>
                <p className="text-sm sm:text-base">{SUBTITLE}</p>
            </div>
        )
    }
    return (
        // next/image 를 쓰지 않는 이유: 파일이 없을 때 onError 로 대체 배너를 그리는 단순한 폴백이 필요하다.
        // 두 장을 다 두고 CSS 로 고른다 — 테마 전환에 JS 가 끼지 않아 하이드레이션 깜박임이 없다.
        <>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={BANNER_IMAGE_LIGHT} alt={`${LABEL} — ${TITLE} — ${SUBTITLE}`} className="block w-full dark:hidden" onError={() => setImageMissing(true)} />
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={BANNER_IMAGE_DARK} alt="" aria-hidden className="hidden w-full dark:block" onError={() => setImageMissing(true)} />
        </>
    )
}

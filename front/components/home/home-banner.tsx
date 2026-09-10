"use client"

import { useState } from "react"
import { Download } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"

/**
 * 헤더 아래 전체 폭 소개 배너 — 우아한 기술블로그의 민트 배너 자리. 클릭하면 이력서가 모달로 열린다.
 *   - 배너 이미지: public/home-banner.png (3840×766, scripts/home-banner.html 을 헤드리스 크롬으로 찍은 것 — 문구를 바꾸면 다시 찍는다)
 *   - 이력서: public/resume.pdf (사용자 결정으로 레포에 포함). 모달은 브라우저 PDF 뷰어(툴바·썸네일 패널) 대신
 *     public/resume.png — scripts/render-resume.sh 로 PDF 첫 장을 렌더한 이미지 — 만 보인다. PDF 를 바꾸면 다시 렌더한다.
 * 이미지가 없으면(404) 같은 문구의 CSS 배너로 대체해 화면이 깨지지 않는다.
 */
export const BANNER_IMAGE = "/home-banner.png"
export const RESUME_URL = "/resume.pdf"
export const RESUME_IMAGE = "/resume.png"
const LABEL = "WOOBEEBLOG"
const TITLE = "백엔드엔지니어 김병우"
const SUBTITLE = "관심 있는 것을 공부합니다"

export default function HomeBanner() {
    const [imageMissing, setImageMissing] = useState(false)
    const [open, setOpen] = useState(false)
    return (
        <>
            <button
                type="button"
                onClick={() => setOpen(true)}
                aria-label={`${TITLE} — 이력서 보기`}
                title="클릭하면 이력서가 열립니다"
                className="group block w-full overflow-hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
                {imageMissing ? (
                    <div className="flex h-44 w-full flex-col items-center justify-center gap-2 bg-[#0EEDD2] px-4 text-center text-neutral-900 transition-opacity group-hover:opacity-90 sm:h-56">
                        <span className="text-xs font-bold tracking-[0.1em]">{LABEL}</span>
                        <h2 className="text-3xl font-black tracking-tight sm:text-5xl">{TITLE}</h2>
                        <p className="text-sm sm:text-base">{SUBTITLE}</p>
                    </div>
                ) : (
                    // next/image 를 쓰지 않는 이유: 파일이 없을 때 onError 로 대체 배너를 그리는 단순한 폴백이 필요하다
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                        src={BANNER_IMAGE}
                        alt={`${LABEL} — ${TITLE} — ${SUBTITLE}`}
                        className="w-full transition-opacity group-hover:opacity-90"
                        onError={() => setImageMissing(true)}
                    />
                )}
            </button>

            <Dialog open={open} onOpenChange={setOpen}>
                <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto p-0">
                    <DialogHeader className="sr-only"><DialogTitle>이력서</DialogTitle></DialogHeader>
                    {/* 이력서 한 장만 — 뷰어 UI 없이. PDF 원본은 아래 작은 링크로 */}
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={RESUME_IMAGE} alt="이력서 — 백엔드 개발자 김병우" className="block w-full bg-white" />
                    <div className="flex justify-end border-t bg-background px-4 py-2">
                        <Button asChild variant="ghost" size="sm" className="text-muted-foreground">
                            <a href={RESUME_URL} download="김병우_이력서.pdf">
                                <Download className="mr-1.5 h-4 w-4" />PDF 다운로드
                            </a>
                        </Button>
                    </div>
                </DialogContent>
            </Dialog>
        </>
    )
}

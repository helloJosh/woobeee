"use client"

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"

/**
 * 이력서 모달 — 헤더의 RESUME 탭이 연다. 브라우저 PDF 뷰어 대신 public/resume.png(scripts/render-resume.sh 로
 * public/resume.pdf 첫 장을 렌더한 이미지) 한 장만 보인다. PDF 를 바꾸면 다시 렌더한다.
 * 닫기(×)는 흰 종이 위에서 보이도록 어두운 원 배경으로 덮어쓴다.
 */
export const RESUME_IMAGE = "/resume.png"

export default function ResumeDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto p-0 [&>button]:right-3 [&>button]:top-3 [&>button]:rounded-full [&>button]:bg-neutral-900/80 [&>button]:p-1.5 [&>button]:text-white [&>button]:opacity-100 [&>button]:shadow hover:[&>button]:bg-neutral-900">
                <DialogHeader className="sr-only"><DialogTitle>이력서</DialogTitle></DialogHeader>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={RESUME_IMAGE} alt="이력서 — 백엔드 개발자 김병우" className="block w-full bg-white" />
            </DialogContent>
        </Dialog>
    )
}

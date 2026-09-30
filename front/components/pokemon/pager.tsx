"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"

/** 목록 아래 페이지 이동. 한 페이지뿐이면 그리지 않는다. */
export default function Pager({
    page,
    totalPages,
    total,
    onChange,
}: {
    page: number
    totalPages: number
    total: number
    onChange: (next: number) => void
}) {
    if (totalPages <= 1) {
        return null
    }

    return (
        <div className="flex items-center justify-between gap-2 pt-2">
            <span className="text-xs text-muted-foreground">전체 {total}건</span>
            <div className="flex items-center gap-2">
                <Button
                    size="icon"
                    variant="outline"
                    aria-label="이전 페이지"
                    disabled={page <= 1}
                    onClick={() => onChange(page - 1)}
                >
                    <ChevronLeft className="h-4 w-4" />
                </Button>
                <span className="text-sm tabular-nums">
                    {page} / {totalPages}
                </span>
                <Button
                    size="icon"
                    variant="outline"
                    aria-label="다음 페이지"
                    disabled={page >= totalPages}
                    onClick={() => onChange(page + 1)}
                >
                    <ChevronRight className="h-4 w-4" />
                </Button>
            </div>
        </div>
    )
}

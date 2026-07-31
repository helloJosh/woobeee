"use client"

import { ArrowUp } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useScrollToTop } from "@/hooks/use-scroll-to-top"
import { cn } from "@/lib/utils"

interface MinimalScrollToTopProps {
    className?: string
    threshold?: number
}

export default function MinimalScrollToTop({ className, threshold = 400 }: MinimalScrollToTopProps) {
    const { isVisible, isScrolling, scrollToTop } = useScrollToTop({ threshold, behavior: "smooth" })

    if (!isVisible) return null

    return (
        <div className={cn("flex justify-center w-full py-6", className)}>
            <Button
                onClick={scrollToTop}
                variant="outline"
                size="icon"
                className={cn(
                    "h-12 w-12 rounded-full bg-background/80 backdrop-blur-sm border-2 hover:bg-background transition-all duration-300",
                    "hover:scale-105 active:scale-95 shadow-md hover:shadow-lg",
                    isScrolling && "animate-pulse cursor-not-allowed",
                    "border-border hover:border-primary/50",
                )}
            >
                <ArrowUp className={cn("h-5 w-5", isScrolling && "animate-bounce")} />
                <span className="sr-only">맨 위로 이동</span>
            </Button>
        </div>
    )
}

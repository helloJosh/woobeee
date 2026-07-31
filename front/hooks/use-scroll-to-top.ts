"use client"

import { useState, useEffect, useCallback } from "react"

interface UseScrollToTopProps {
    threshold?: number
    behavior?: ScrollBehavior
}

export function useScrollToTop({ threshold = 400, behavior = "smooth" }: UseScrollToTopProps = {}) {
    const [isVisible, setIsVisible] = useState(false)
    const [isScrolling, setIsScrolling] = useState(false)

    // 스크롤 위치 감지
    useEffect(() => {
        const toggleVisibility = () => {
            if (window.pageYOffset > threshold) {
                setIsVisible(true)
            } else {
                setIsVisible(false)
            }
        }

        const handleScroll = () => {
            toggleVisibility()

            // 스크롤 중임을 표시
            setIsScrolling(true)

            // 스크롤이 멈춘 후 상태 업데이트
            clearTimeout(window.scrollTimeout)
            window.scrollTimeout = setTimeout(() => {
                setIsScrolling(false)
            }, 150)
        }

        window.addEventListener("scroll", handleScroll)
        toggleVisibility() // 초기 상태 설정

        return () => {
            window.removeEventListener("scroll", handleScroll)
            clearTimeout(window.scrollTimeout)
        }
    }, [threshold])

    // TOP으로 스크롤
    const scrollToTop = useCallback(() => {
        setIsScrolling(true)

        // 현재 스크롤 위치 저장
        const startPosition = window.pageYOffset

        // 스크롤이 이미 맨 위에 있다면 바로 완료
        if (startPosition <= 0) {
            setIsScrolling(false)
            return
        }

        // 스크롤 시작
        window.scrollTo({
            top: 0,
            behavior,
        })

        // 스크롤 완료 감지
        let scrollCheckCount = 0
        const maxChecks = 100 // 최대 체크 횟수

        const checkScrollComplete = () => {
            scrollCheckCount++
            const currentPosition = window.pageYOffset

            // 맨 위에 도달했거나 최대 체크 횟수에 도달한 경우
            if (currentPosition <= 0 || scrollCheckCount >= maxChecks) {
                setIsScrolling(false)
                return
            }

            // 스크롤이 멈춘 경우 (위치가 변하지 않는 경우)
            setTimeout(() => {
                if (window.pageYOffset === currentPosition) {
                    setIsScrolling(false)
                } else {
                    requestAnimationFrame(checkScrollComplete)
                }
            }, 50)
        }

        // 스크롤 완료 체크 시작
        setTimeout(() => {
            requestAnimationFrame(checkScrollComplete)
        }, 100)
    }, [behavior])

    return {
        isVisible,
        isScrolling,
        scrollToTop,
    }
}

// Window 타입 확장
declare global {
    interface Window {
        scrollTimeout: NodeJS.Timeout
    }
}

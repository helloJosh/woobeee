"use client"

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react"

export interface HeaderControls {
  /** 모바일에서 사이드바(카테고리)를 여닫는 콜백. 없으면 헤더는 햄버거 버튼을 숨긴다. */
  onToggleSidebar?: () => void
  /** 헤더 검색창에 표시할 현재 검색어. */
  searchQuery?: string | null
  /** 헤더 검색창 제출 시 호출. 없으면 헤더는 검색창을 숨긴다. */
  onSearchChange?: (query: string) => void
}

type RegisterFn = (controls: HeaderControls) => () => void

// 값(controls)과 등록 함수(register)를 서로 다른 컨텍스트로 나눈다.
// register는 useState의 setter를 감싼 것뿐이라 정체성이 절대 바뀌지 않고,
// controls는 등록될 때마다 바뀐다. 이 둘을 하나의 컨텍스트 값으로 묶어서 같이
// 메모했다면 "등록 → controls 갱신 → 새 컨텍스트 값 → register에 의존하는
// effect가 재실행 → 재등록 → ..." 로 이어지는 무한 루프가 생긴다. Header는
// controls만 구독하고 useRegisterHeaderControls는 register만 구독하므로
// 서로의 리렌더가 서로를 다시 트리거하지 않는다.
const HeaderControlsValueContext = createContext<HeaderControls>({})
const HeaderControlsRegisterContext = createContext<RegisterFn | null>(null)

export function HeaderControlsProvider({ children }: { children: ReactNode }) {
  const [controls, setControls] = useState<HeaderControls>({})

  const register = useCallback<RegisterFn>((next) => {
    setControls(next)
    return () => setControls({})
  }, [])

  return (
      <HeaderControlsRegisterContext.Provider value={register}>
        <HeaderControlsValueContext.Provider value={controls}>
          {children}
        </HeaderControlsValueContext.Provider>
      </HeaderControlsRegisterContext.Provider>
  )
}

/** Header가 소비한다. 등록된 페이지가 없으면(대부분의 라우트) 빈 컨트롤 = 탭 3개만. */
export function useHeaderControls(): HeaderControls {
  return useContext(HeaderControlsValueContext)
}

/**
 * 사이드바/검색이 있는 페이지(예: /blog)가 마운트되어 있는 동안 자신의 헤더 컨트롤을
 * 등록하고, 언마운트 시 정리한다(다른 라우트로 이동했을 때 헤더에 낡은 검색창이
 * 남지 않도록). 콜백은 ref로 최신 상태를 유지하고, 실제로 컨텍스트에 등록하는
 * 함수는 안정적인 델리게이트라서 호출자가 매 렌더 새 인라인 함수를 넘기더라도
 * 재등록 루프가 생기지 않는다.
 */
export function useRegisterHeaderControls(controls: HeaderControls) {
  const register = useContext(HeaderControlsRegisterContext)
  const controlsRef = useRef(controls)
  controlsRef.current = controls

  const hasToggle = Boolean(controls.onToggleSidebar)
  const hasSearch = Boolean(controls.onSearchChange)

  useEffect(() => {
    if (!register) return

    return register({
      onToggleSidebar: hasToggle ? () => controlsRef.current.onToggleSidebar?.() : undefined,
      onSearchChange: hasSearch ? (query: string) => controlsRef.current.onSearchChange?.(query) : undefined,
      searchQuery: controlsRef.current.searchQuery,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [register, hasToggle, hasSearch, controls.searchQuery])
}

"use client"

import {
    ApiResponse,
    CartResponse,
    CreateRoomResult,
    GamePrincipalView,
    GameResultSummary,
    GameType,
    GetCommentResponse,
    GetPostResponse,
    GetPostsResponse,
    GoogleAuthorizationResponse,
    GuestTokenResult,
    MemberProfile,
    PresignedUploadResponse,
    PostCommentRequest,
    ProductCreateRequest,
    ProductCreateResponse,
    ProductListResponse,
    ProductSummary,
    ProductsParams,
    PostsParams,
    RoomSummary,
    TokenResponse
} from "./types"
import {getFriendlyErrorMessage} from "@/lib/errors/error-utils";

// API 기본 설정
// 빈 문자열 = 동일 오리진. Next rewrites(next.config.mjs)가 /api/auth, /api/back -> app-mvc,
// /api/game -> app-webflux 로 프록시한다. 백엔드를 직접 호출해야 하면 NEXT_PUBLIC_API_BASE_URL 설정.
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? ""
const REFRESH_ENDPOINT = "/api/auth/refresh-tokens"

// refresh 까지 실패한 인증 만료. 호출부(예: 글 편집기)가 이 값으로 구분해
// 재로그인 유도 UI 를 그린다 — 문자열 비교가 계약이므로 여기서만 정의한다.
export const AUTH_EXPIRED_MESSAGE = "인증이 만료되었습니다. 다시 로그인해 주세요."


export class HttpError extends Error {
    status: number
    data: any
    constructor(status: number, message: string, data?: any) {
        super(message)
        this.status = status
        this.data = data
    }
}

// 토큰 관리
export const tokenManager = {
    getToken: () => {
        if (typeof window !== "undefined") {
            return localStorage.getItem("accessToken")
        }
        return null
    },

    setToken: (token: string) => {
        if (typeof window !== "undefined") {
            localStorage.setItem("accessToken", token)
        }
    },

    getRefreshToken: () => {
        if (typeof window !== "undefined") {
            return localStorage.getItem("refreshToken")
        }
        return null
    },

    setRefreshToken: (token: string) => {
        if (typeof window !== "undefined") {
            localStorage.setItem("refreshToken", token)
        }
    },

    setTokens: (tokens: TokenResponse) => {
        tokenManager.setToken(tokens.accessToken)
        tokenManager.setRefreshToken(tokens.refreshToken)
        if (typeof window !== "undefined") {
            if (tokens.memberId !== undefined) {
                localStorage.setItem("authMemberId", String(tokens.memberId))
            }
            if (tokens.role) {
                localStorage.setItem("authRole", tokens.role)
            }
        }
    },

    getMemberId: () => {
        if (typeof window === "undefined") {
            return null
        }

        const value = localStorage.getItem("authMemberId")
        return value ? Number(value) : null
    },

    getRole: () => {
        if (typeof window !== "undefined") {
            return localStorage.getItem("authRole")
        }
        return null
    },

    /**
     * 액세스 토큰만 버린다. 리프레시 토큰과 신원(memberId·role)은 남긴다.
     *
     * <p>{@link removeToken} 과 나눠 둔 이유: "이 액세스 토큰은 죽었다" 와 "이 세션은 끝났다"
     * 는 전혀 다른 사건인데, 하나뿐이던 시절에는 앞엣것을 만날 때마다 뒤엣것을 실행했다.
     * 만료된 액세스 토큰은 <b>리프레시 토큰이 고칠 수 있는</b> 상태다 — 함께 지워 버리면
     * 고칠 수단까지 없애고 사용자를 blog·auth 에서도 조용히 로그아웃시킨다.
     *
     * <p>이것만 지우면 다음 인증 요청이 401 을 받고 {@code refreshAccessToken} 이 리프레시
     * 토큰으로 새 액세스 토큰을 받아 온다 — 사용자는 아무것도 눈치채지 못한다. 리프레시까지
     * 죽어 있었다면 그때 {@code handleUnauthorized} 가 정식으로 세션을 끝낸다.
     */
    removeAccessToken: () => {
        if (typeof window !== "undefined") {
            localStorage.removeItem("accessToken")
        }
    },

    /** 세션 전체를 끝낸다. 로그아웃과 갱신까지 실패한 401 이 쓴다. */
    removeToken: () => {
        if (typeof window !== "undefined") {
            localStorage.removeItem("accessToken")
            localStorage.removeItem("refreshToken")
            localStorage.removeItem("authMemberId")
            localStorage.removeItem("authRole")
        }
    },
}

const isApiSuccessful = (response: ApiResponse<unknown>) => {
    return response.header.successful ?? response.header.isSuccessful ?? false
}

const getDevice = () => {
    if (typeof window === "undefined") {
        return "web"
    }

    const stored = localStorage.getItem("authDevice")
    if (stored) {
        return stored
    }

    const randomValue = window.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`
    const device = `web:${randomValue}`
    localStorage.setItem("authDevice", device)
    return device
}

const getLanguage = () => {
    let lang = "ko-KR"
    if (typeof window !== "undefined") {
        const stored = localStorage.getItem("lang")
        if (stored === "ko-KR" || stored === "en-US") {
            lang = stored
        }
    }
    return lang
}

const handleUnauthorized = () => {
    tokenManager.removeToken()
    if (typeof window !== "undefined") {
        alert("인증만료되었습니다. 다시 로그인해주세요")
        window.location.reload()
    }
}

let refreshPromise: Promise<boolean> | null = null

const refreshAccessToken = async (): Promise<boolean> => {
    if (refreshPromise) {
        return refreshPromise
    }

    refreshPromise = (async () => {
        const refreshToken = tokenManager.getRefreshToken()
        if (!refreshToken) {
            return false
        }

        try {
            const response = await fetch(`${API_BASE_URL}${REFRESH_ENDPOINT}`, {
                method: "POST",
                credentials: "include",
                headers: {
                    "Content-Type": "application/json",
                    "X-Lang": getLanguage(),
                },
                body: JSON.stringify({
                    refreshToken,
                    device: getDevice(),
                }),
            })

            if (!response.ok) {
                return false
            }

            const json: ApiResponse<TokenResponse> = await response.json()
            if (!isApiSuccessful(json) || !json.data?.accessToken || !json.data?.refreshToken) {
                return false
            }

            tokenManager.setTokens(json.data)
            return true
        } catch (error) {
            console.error("Token refresh failed:", error)
            return false
        } finally {
            refreshPromise = null
        }
    })()

    return refreshPromise
}

const shouldTryRefresh = (endpoint: string) => {
    return ![
        "/api/auth/login",
        "/api/auth/signup",
        "/api/auth/callback-google",
        "/api/auth/logout",
        REFRESH_ENDPOINT,
    ].includes(endpoint)
}

// API 요청 함수
// @ts-ignore
export const apiRequest = async (
    endpoint: string,
    options: RequestInit = {},
    retryOnUnauthorized = true,
    // 옵션 객체로 받는다 — 불리언 두 개가 나란히 놓이면(`true, true`) 호출부에서 어느 쪽이
    // 무엇인지 알 수 없고, 플래그가 하나 더 늘 때마다 나빠진다.
    // 기본값은 기존 호출부(blog/auth/cart/product) 동작을 그대로 유지한다.
    // game API처럼 화면에 인라인 에러를 직접 그리는 호출부만 suppressAlert 를 켠다.
    //
    // suppressUnauthorizedHandler: 401(그리고 갱신 실패) 때 전역 세션 만료 처리를 건너뛴다.
    // 그 처리는 토큰을 전부 지우고 alert 를 띄운 뒤 페이지를 새로고침한다 — 사용자가 직접
    // 누른 요청에는 맞지만, **화면 뒤에서 도는 확인용 호출**에는 재앙이다. 게임 중
    // gameAPI.me() 같은 배경 호출 하나가 멀쩡히 돌아가는 판을 통째로 날려 버린다.
    // 켜면 그냥 던진다 — 호출부가 조용히 대체 경로로 가면 된다.
    {
        suppressAlert = false,
        suppressUnauthorizedHandler = false,
    }: { suppressAlert?: boolean; suppressUnauthorizedHandler?: boolean } = {}
) => {
    const url = `${API_BASE_URL}${endpoint}`
    const token = tokenManager.getToken()
    const lang = getLanguage()

    const config: RequestInit = {
        ...options,
        credentials: "include",
        headers: {
            // multipart(FormData)는 브라우저가 boundary 포함 Content-Type을 직접 정해야 한다
            ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
            "X-Lang": lang,                  // ▼ 여기서 무조건 X-Lang 넣어줌
            ...(token && { Authorization: `Bearer ${token}` }),
            ...options.headers,
        },
    }

    try {
        const response = await fetch(url, config)

        // 응답 자체는 왔지만 HTTP 에러인 경우
        if (!response.ok) {
            if (response.status === 401) {
                const canRefresh = shouldTryRefresh(endpoint)
                if (retryOnUnauthorized && canRefresh) {
                    const refreshed = await refreshAccessToken()
                    if (refreshed) {
                        return apiRequest(endpoint, options, false, {
                            suppressAlert,
                            suppressUnauthorizedHandler,
                        })
                    }
                }

                // 토큰을 지우지도, alert 를 띄우지도, 새로고침하지도 않는다. 배경 호출
                // 하나가 세션과 화면을 함께 끝내면 안 된다.
                if (suppressUnauthorizedHandler) {
                    throw new Error(AUTH_EXPIRED_MESSAGE)
                }

                if (canRefresh) {
                    handleUnauthorized()
                } else {
                    tokenManager.removeToken()
                }
                throw new Error(AUTH_EXPIRED_MESSAGE)
            }
            let code = "unknown"
            let description = "요청에 실패했습니다."

            try {
                const errorData = await response.json()
                const header = errorData?.header
                const failed = header?.successful === false || header?.isSuccessful === false
                code = failed ? header?.message ?? "unknown" : "unknown"
                description = getFriendlyErrorMessage(code)

                console.log(description)
                if (!suppressAlert) {
                    alert(description)
                }
            } catch {
            }

            throw new Error(description)
        }
        return response
    } catch (error) {
        console.error("API request failed:", error)
        throw error
    }
}

export type UploadProgressHandler = (loadedBytes: number, totalBytes: number) => void

// fetch는 업로드 진행률을 주지 않는다 — 대용량 multipart(게시글 첨부)만 XHR로 보낸다.
// apiRequest와 동일하게 401이면 refresh 후 한 번 재시도한다.
const uploadRequest = async (
    endpoint: string,
    method: "POST" | "PUT",
    form: FormData,
    onProgress?: UploadProgressHandler,
    retryOnUnauthorized = true,
): Promise<ApiResponse<void>> => {
    const send = () =>
        new Promise<{ status: number; json: ApiResponse<void> | null }>((resolve, reject) => {
            const xhr = new XMLHttpRequest()
            xhr.open(method, `${API_BASE_URL}${endpoint}`)
            xhr.withCredentials = true
            const token = tokenManager.getToken()
            if (token) {
                xhr.setRequestHeader("Authorization", `Bearer ${token}`)
            }
            xhr.setRequestHeader("X-Lang", getLanguage())
            xhr.upload.onprogress = (event) => {
                if (event.lengthComputable) {
                    onProgress?.(event.loaded, event.total)
                }
            }
            xhr.onload = () => {
                let json: ApiResponse<void> | null = null
                try {
                    json = JSON.parse(xhr.responseText)
                } catch {
                }
                resolve({ status: xhr.status, json })
            }
            xhr.onerror = () => reject(new Error("네트워크 오류로 업로드에 실패했습니다."))
            xhr.send(form)
        })

    const result = await send()

    if (result.status === 401 && retryOnUnauthorized) {
        const refreshed = await refreshAccessToken()
        if (refreshed) {
            return uploadRequest(endpoint, method, form, onProgress, false)
        }
        throw new Error(AUTH_EXPIRED_MESSAGE)
    }

    if (result.json === null) {
        throw new Error(`업로드에 실패했습니다. (HTTP ${result.status})`)
    }
    return result.json
}

// 인증 API
export const authAPI = {
    startGoogleLogin: async (): Promise<GoogleAuthorizationResponse> => {
        const response = await apiRequest("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({
                device: getDevice(),
            }),
        })
        const json: ApiResponse<GoogleAuthorizationResponse> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "Google 로그인 시작에 실패했습니다.")
        }

        return json.data
    },

    startSignup: async (nickname: string): Promise<GoogleAuthorizationResponse> => {
        const response = await apiRequest("/api/auth/signup", {
            method: "POST",
            body: JSON.stringify({
                nickname,
                termsAgreed: true,
                privacyPolicyAgreed: true,
                device: getDevice(),
            }),
        })
        const json: ApiResponse<GoogleAuthorizationResponse> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "Google 회원가입 시작에 실패했습니다.")
        }

        return json.data
    },

    completeGoogleAuthorization: async (code: string, state: string): Promise<ApiResponse<TokenResponse>> => {
        const controller = new AbortController()
        const timeoutId = window.setTimeout(() => controller.abort(), 15000)

        let response: Response | null = null
        try {
            response = await apiRequest("/api/auth/callback-google", {
                method: "POST",
                body: JSON.stringify({ code, state }),
                signal: controller.signal,
            })
        } finally {
            window.clearTimeout(timeoutId)
        }

        if (!response) {
            throw new Error("Google 로그인 처리에 실패했습니다.")
        }

        const json: ApiResponse<TokenResponse> = await response.json()
        if (!isApiSuccessful(json) || !json.data?.accessToken || !json.data?.refreshToken) {
            throw new Error(json.header.message || "Google 로그인 처리에 실패했습니다.")
        }

        return json
    },

    // 로그아웃
    logout: async () => {
        await fetch(`${API_BASE_URL}/api/auth/logout`, {
            method: "GET",
            credentials: "include",
            headers: {
                "X-Lang": getLanguage(),
            },
        })
        tokenManager.removeToken()
    },

    me: async (): Promise<MemberProfile> => {
        const response = await apiRequest("/api/auth/me")
        const json: ApiResponse<MemberProfile> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "프로필을 불러오지 못했습니다.")
        }
        return json.data
    },
}

/**
 * 게임 API 호출이 공통으로 쓰는 apiRequest 옵션.
 *
 * <p>`suppressAlert`: 게임 화면은 `alert()` 대신 화면에 인라인 에러를 직접 그린다. 켜 두지
 * 않으면 같은 실패가 네이티브 대화상자와 배너로 두 번 안내된다.
 *
 * <p>`suppressUnauthorizedHandler`: 갱신까지 실패한 401 에 대해 전역 세션 만료 처리를
 * 건너뛴다. 그 처리는 토큰을 전부 지우고 `alert` 를 띄운 뒤 페이지를 새로고침하는데,
 * 게임 화면은 <b>전부</b> 인라인 배너 계약 위에 지어져 있어서 그 새로고침이 무엇을 하든
 * 화면의 약속을 어긴다.
 *
 * <p>특히 나쁜 것이 `game_memberNotFound` 다 — 이 코드는 401 이므로(GameErrorCode:33,
 * RoomController:54 에서 던진다) 회원 행이 사라진 사람은 "갱신 성공 → 재시도 401 → 세션
 * 파기 → 새로고침 → 처음부터" 를 무한히 돈다. 401 을 만든 원인이 액세스 토큰이 아닌데
 * 액세스 토큰을 지우는 것이라 아무리 반복해도 나아지지 않는다.
 *
 * <p>기본값도, 게임 밖 호출자(auth·blog)도 바꾸지 않는다. 그쪽은 사용자가 직접 누른
 * 요청이고 세션 만료 처리가 맞는 대응이다.
 */
const GAME_REQUEST_OPTIONS = { suppressAlert: true, suppressUnauthorizedHandler: true } as const

// 게임 API
export const gameAPI = {
    createRoom: async (gameType: GameType): Promise<CreateRoomResult> => {
        const response = await apiRequest(
            "/api/game/rooms",
            {
                method: "POST",
                body: JSON.stringify({ gameType }),
            },
            true,
            GAME_REQUEST_OPTIONS
        )
        const json: ApiResponse<CreateRoomResult> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "방을 만들지 못했습니다.")
        }
        return json.data
    },

    getRoomSummary: async (roomId: string, inviteCode: string): Promise<RoomSummary> => {
        const response = await apiRequest(
            `/api/game/rooms/${encodeURIComponent(roomId)}?invite=${encodeURIComponent(inviteCode)}`,
            {},
            true,
            GAME_REQUEST_OPTIONS
        )
        const json: ApiResponse<RoomSummary> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "방 정보를 불러오지 못했습니다.")
        }
        return json.data
    },

    issueGuestToken: async (
        roomId: string,
        inviteCode: string,
        nickname: string
    ): Promise<GuestTokenResult> => {
        const response = await apiRequest(
            `/api/game/rooms/${encodeURIComponent(roomId)}/guest-tokens`,
            {
                method: "POST",
                body: JSON.stringify({ inviteCode, nickname }),
            },
            true,
            GAME_REQUEST_OPTIONS
        )
        const json: ApiResponse<GuestTokenResult> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "닉네임으로 참가하지 못했습니다.")
        }
        return json.data
    },

    /**
     * 토큰이 말하는 나. 회원 전용이다 — 게임 서버의 `GamePrincipals.require` 는 인증이
     * 없으면 던지므로, 로그인하지 않은 방문자(게스트)에게는 부르면 안 된다.
     *
     * <p>여기서 401 처리를 끄는 것이 특히 중요하다. 플레이 화면이 배경에서 신원을 확인하려고
     * 부르는 것이라, 전역 세션 만료 처리가 돌면 액세스 토큰이 만료된 채 게스트 토큰으로
     * 멀쩡히 두고 있던 판이 alert 한 번과 새로고침으로 끝난다. 실패는 조용히 던지고,
     * useVerifiedMemberId 가 저장된 값으로 되돌아간다. (지금은 게임 호출 전부가 그렇다 —
     * {@link GAME_REQUEST_OPTIONS} 참고.)
     */
    me: async (): Promise<GamePrincipalView> => {
        const response = await apiRequest("/api/game/me", {}, true, GAME_REQUEST_OPTIONS)
        const json: ApiResponse<GamePrincipalView> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "내 정보를 불러오지 못했습니다.")
        }
        return json.data
    },

    myResults: async (limit = 20, offset = 0): Promise<GameResultSummary[]> => {
        const response = await apiRequest(
            `/api/game/me/results?limit=${limit}&offset=${offset}`,
            {},
            true,
            GAME_REQUEST_OPTIONS
        )
        const json: ApiResponse<GameResultSummary[]> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "전적을 불러오지 못했습니다.")
        }
        return json.data
    },

    replayUrl: async (gameResultId: number): Promise<string> => {
        const response = await apiRequest(
            `/api/game/results/${gameResultId}/replay`,
            {},
            true,
            GAME_REQUEST_OPTIONS
        )
        const json: ApiResponse<{ replayUrl: string }> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "기보를 불러오지 못했습니다.")
        }
        return json.data.replayUrl
    },
}

export const categoryAPI = {
    categories: async () => {
        const response = await apiRequest("/api/back/categories", {
            method: "GET"
        })

        if (!response.ok) {
            const error = await response.json()
            throw new Error(error.message || "카테고리 조회 실패.")
        }

        const json = await response.json()
        return json.data
    }
}

export const postsAPI = {
    getPosts: async (params: PostsParams = {}): Promise<GetPostsResponse> => {
        const searchParams = new URLSearchParams()

        if (params.page !== undefined) searchParams.append("page", String(params.page))
        if (params.size !== undefined) searchParams.append("size", String(params.size))
        if (params.q && params.q.trim() !== "") {
            searchParams.append("q", params.q)
        }
        if (params.categoryId !== undefined && params.categoryId !== null) {
            searchParams.append("categoryId", String(params.categoryId))
        }

        const response = await apiRequest(`/api/back/posts?${searchParams.toString()}`, {
            method: "GET"
        })

        if (!response.ok) {
            const error = await response.json()
            throw new Error(error.message || "포스트를 가져오는데 실패했습니다.")
        }

        const apiResponse: ApiResponse<GetPostsResponse> = await response.json()

        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "포스트를 가져오는데 실패했습니다.")
        }

        return apiResponse.data
    },

    getPost: async (postId: number): Promise<GetPostResponse> => {
        const res = await apiRequest(`/api/back/posts/${postId}`, {
            method: "GET"
        })

        if (!res.ok) {
            throw new Error("Failed to fetch post")
        }

        const data: ApiResponse<GetPostResponse> = await res.json()
        return data.data
    },

    // 수정 화면용 — 언어별 원문을 각각 받아온다 (서버는 Accept-Language로 언어를 고른다)
    getPostByLocale: async (postId: number, locale: "ko-KR" | "en"): Promise<GetPostResponse> => {
        const res = await apiRequest(`/api/back/posts/${postId}`, {
            method: "GET",
            headers: { "Accept-Language": locale },
        })

        if (!res.ok) {
            throw new Error("Failed to fetch post")
        }

        const data: ApiResponse<GetPostResponse> = await res.json()
        return data.data
    },

    createPost: async (form: FormData, onProgress?: UploadProgressHandler): Promise<void> => {
        const json = await uploadRequest("/api/back/posts", "POST", form, onProgress)
        if (!isApiSuccessful(json)) {
            throw new Error(json.header?.message || "글 저장에 실패했습니다.")
        }
    },

    updatePost: async (postId: number, form: FormData, onProgress?: UploadProgressHandler): Promise<void> => {
        const json = await uploadRequest(`/api/back/posts/${postId}`, "PUT", form, onProgress)
        if (!isApiSuccessful(json)) {
            throw new Error(json.header?.message || "글 수정에 실패했습니다.")
        }
    },

    deletePost: async (postId: number): Promise<void> => {
        const response = await apiRequest(`/api/back/posts/${postId}`, {
            method: "DELETE",
        })

        const json: ApiResponse<void> = await response.json()
        if (!isApiSuccessful(json)) {
            throw new Error(json.header.message || "글 삭제에 실패했습니다.")
        }
    },
}

export const productAPI = {
    getProducts: async (params: ProductsParams = {}): Promise<ProductListResponse> => {
        const searchParams = new URLSearchParams()

        searchParams.append("page", String(params.page ?? 0))
        searchParams.append("size", String(params.size ?? 16))
        if (params.q && params.q.trim() !== "") {
            searchParams.append("q", params.q.trim())
        }
        if (params.tag && params.tag.trim() !== "") {
            searchParams.append("tag", params.tag.trim())
        }
        if (params.artist && params.artist.trim() !== "") {
            searchParams.append("artist", params.artist.trim())
        }

        const response = await apiRequest(`/api/products?${searchParams.toString()}`, {
            method: "GET",
        })

        const apiResponse: ApiResponse<ProductListResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "상품을 가져오는데 실패했습니다.")
        }

        return apiResponse.data
    },

    getProduct: async (productId: number): Promise<ProductSummary> => {
        const response = await apiRequest(`/api/products/${productId}`, {
            method: "GET",
        })

        const apiResponse: ApiResponse<ProductSummary> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "상품을 가져오는데 실패했습니다.")
        }

        return apiResponse.data
    },

    createImagePresignedUrl: async (file: File): Promise<PresignedUploadResponse> => {
        const response = await apiRequest("/api/products/images", {
            method: "POST",
            body: JSON.stringify({
                fileName: file.name,
                contentType: file.type || "application/octet-stream",
            }),
        })

        const apiResponse: ApiResponse<PresignedUploadResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "이미지 업로드 URL을 발급하지 못했습니다.")
        }

        return apiResponse.data
    },

    uploadImage: async (file: File, uploadUrl: string) => {
        const response = await fetch(uploadUrl, {
            method: "PUT",
            headers: {
                "Content-Type": file.type || "application/octet-stream",
            },
            body: file,
        })

        if (!response.ok) {
            throw new Error("이미지 업로드에 실패했습니다.")
        }
    },

    createProduct: async (request: ProductCreateRequest): Promise<ProductCreateResponse> => {
        const response = await apiRequest("/api/products", {
            method: "POST",
            body: JSON.stringify(request),
        })

        const apiResponse: ApiResponse<ProductCreateResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "상품 등록에 실패했습니다.")
        }

        return apiResponse.data
    },
}

// 장바구니 API
// cartId=0은 "구매자의 현재 활성 장바구니"를 의미하는 sentinel이다.
// 조회/추가 모두 0으로 호출하면 백엔드가 활성 장바구니를 찾아준다.
const CURRENT_CART = 0

export const cartAPI = {
    getCart: async (buyerId: number): Promise<CartResponse> => {
        const response = await apiRequest(`/api/buyers/${buyerId}/carts/${CURRENT_CART}`, {
            method: "GET",
        })

        const apiResponse: ApiResponse<CartResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "장바구니를 가져오는데 실패했습니다.")
        }

        return apiResponse.data
    },

    addProduct: async (buyerId: number, productId: number): Promise<CartResponse> => {
        const response = await apiRequest(`/api/buyers/${buyerId}/carts/${CURRENT_CART}`, {
            method: "POST",
            body: JSON.stringify({ productId }),
        })

        const apiResponse: ApiResponse<CartResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "장바구니에 담지 못했습니다.")
        }

        return apiResponse.data
    },

    removeProduct: async (
        buyerId: number,
        cartId: number,
        productId: number
    ): Promise<CartResponse> => {
        const response = await apiRequest(
            `/api/buyers/${buyerId}/carts/${cartId}/products/${productId}`,
            { method: "DELETE" }
        )

        const apiResponse: ApiResponse<CartResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "상품을 삭제하지 못했습니다.")
        }

        return apiResponse.data
    },

    clearCart: async (buyerId: number, cartId: number): Promise<CartResponse> => {
        const response = await apiRequest(`/api/buyers/${buyerId}/carts/${cartId}`, {
            method: "DELETE",
        })

        const apiResponse: ApiResponse<CartResponse> = await response.json()
        if (!isApiSuccessful(apiResponse)) {
            throw new Error(apiResponse.header.message || "장바구니를 비우지 못했습니다.")
        }

        return apiResponse.data
    },
}


type Method = "POST" | "DELETE";
async function call(method: Method, postId: number, userId?: string) {
    const res = await apiRequest(`/api/back/likes/${postId}`, {
        method: method
    })
    // const res = await fetch(`/api/back/likes/${postId}`, {
    //     method,
    //     headers: {
    //         "Content-Type": "application/json",
    //         ...(userId ? { userId } : {}), // 선택 헤더
    //     },
    // });

    if (!res.ok) {
        const msg = await res.text().catch(() => "");
        throw new Error(msg || `Like API ${method} failed (${res.status})`);
    }

    if (res.headers.get("content-type")?.includes("application/json")) {
        return (await res.json()) as ApiResponse<null | void>;
    }

    const fallback: ApiResponse<null> = {
        header: { successful: true, message: "OK", resultCode: res.status },
        data: null,
    };
    return fallback;
}

export const likeAPI = {
    addLike(postId: number, userId?: string) {
        return call("POST", postId, userId);
    },

    deleteLike(postId: number, userId?: string) {
        return call("DELETE", postId, userId);
    },
}


export type GetCommentsApiResponse = ApiResponse<GetCommentResponse[]>
export const commentAPI = {
    async getAllFromPost(
        postId: number,
        userId?: string
    ): Promise<GetCommentsApiResponse> {

        const res = await apiRequest(`/api/back/comments/${postId}`, {
            method: "GET"
        })
        // const res = await fetch(`/api/back/comments/${postId}`, {
        //     method: "GET",
        //     headers: {
        //         Accept: "application/json",
        //         ...(userId ? { userId } : {}),
        //     },
        //     cache: "no-store", // 최신 댓글 보장 (필요시 제거)
        // })

        if (!res.ok) {
            const msg = await res.text().catch(() => "")
            throw new Error(msg || `Comments GET failed (${res.status})`)
        }

        return (await res.json()) as GetCommentsApiResponse
    },
    /**
     * 댓글 저장
     * - POST /api/back/comments
     * - 헤더: userId (선택)
     */
    async saveComment(
        request: PostCommentRequest,
        userId?: string
    ): Promise<ApiResponse<void>> {
        const res = await apiRequest(`/api/back/comments`, {
            method: "POST",
            body: JSON.stringify(request),
        })

        if (!res.ok) {
            const msg = await res.text().catch(() => "")
            throw new Error(msg || `Comments POST failed (${res.status})`)
        }
        return (await res.json()) as ApiResponse<void>
    },

    /**
     * 댓글 삭제
     * - DELETE /api/back/comments/{commentId}
     * - 헤더: userId (선택)
     */
    async deleteComment(
        commentId: number,
        userId?: string
    ): Promise<ApiResponse<void>> {
        const res = await apiRequest(`/api/back/comments/${commentId}`, {
            method: "DELETE"
        })

        if (!res.ok) {
            const msg = await res.text().catch(() => "")
            throw new Error(msg || `Comments DELETE failed (${res.status})`)
        }
        return (await res.json()) as ApiResponse<void>
    },
}

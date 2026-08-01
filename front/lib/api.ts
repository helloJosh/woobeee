"use client"

import {
    ApiResponse,
    CartResponse,
    GetCommentResponse,
    GetPostResponse,
    GetPostsResponse,
    GoogleAuthorizationResponse,
    PresignedUploadResponse,
    PostCommentRequest,
    ProductCreateRequest,
    ProductCreateResponse,
    ProductListResponse,
    ProductSummary,
    ProductsParams,
    PostsParams,
    TokenResponse
} from "./types"
import {getFriendlyErrorMessage} from "@/lib/errors/error-utils";

// API 기본 설정
// 빈 문자열 = 동일 오리진. Next rewrites(next.config.mjs)가 /api/auth, /api/back -> app-mvc,
// /api/game -> app-webflux 로 프록시한다. 백엔드를 직접 호출해야 하면 NEXT_PUBLIC_API_BASE_URL 설정.
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? ""
const REFRESH_ENDPOINT = "/api/auth/refresh-tokens"


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
    retryOnUnauthorized = true
) => {
    const url = `${API_BASE_URL}${endpoint}`
    const token = tokenManager.getToken()
    const lang = getLanguage()

    const config: RequestInit = {
        ...options,
        credentials: "include",
        headers: {
            "Content-Type": "application/json",
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
                        return apiRequest(endpoint, options, false)
                    }
                }

                if (canRefresh) {
                    handleUnauthorized()
                } else {
                    tokenManager.removeToken()
                }
                throw new Error("인증이 만료되었습니다. 다시 로그인해 주세요.")
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
                alert(description)
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
    }
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
    }
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

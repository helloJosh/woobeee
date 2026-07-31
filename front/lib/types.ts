export interface Category {
  id: number
  name: string
  count: number
  children?: Category[]
}

export interface Comment {
  id: number
  author: string
  isEditable: boolean
  content: string
  createdAt: Date
  replies: Comment[]
}


// 사용자 관련 타입 추가
export interface User {
  id: string
  email: string
  name: string
  profileImage?: string
  createdAt: Date
}

export interface AuthResponse {
  user: User
  accessToken: string
  refreshToken: string
}

export interface TokenResponse {
  accessToken: string
  accessTokenExpiresInSeconds: number
  refreshToken: string
  refreshTokenExpiresInSeconds: number
  memberId?: number
  role?: "ROLE_BUYER" | "ROLE_SELLER" | string
}

export interface GoogleAuthorizationResponse {
  authorizationUrl: string
  state: string
  expiresInSeconds: number
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  email: string
  password: string
  name: string
}


export interface ApiResponse<T> {
  header: ApiHeader
  data: T
}

export interface ApiHeader {
  successful?: boolean
  isSuccessful?: boolean
  message: string
  resultCode?: number
}

export interface Post {
  id: number
  title: string
  content: string
  categoryName: string
  categoryId: number
  authorName: string
  views: number
  likes: number
  createdAt: Date
}

export interface GetPostsResponse {
  contents: Post[]
  hasNext: boolean
}

export interface PostsParams {
  page?: number
  size?: number
  categoryId?: number
  q?: string // 검색어
}

export interface GetPostResponse {
  id: number
  title: string
  content: string
  categoryName : string
  categoryId : number
  views: number
  likes: number
  isLiked: boolean
  createdAt: Date
}

export interface GetCommentResponse {
  id: number
  author: string
  content: string
  createdAt: Date
  replies?: GetCommentResponse[]
}

export interface PostCommentRequest {
  postId : number
  parentId : number | null
  content : string
}

export interface ProductSummary {
  productId: number
  sellerId: number
  name: string
  description: string
  artist: string | null
  height: string
  width: string
  shape: string
  material: string
  tags: string[]
  price: number
  status: "IMAGE_PENDING" | "ACTIVE" | "RESERVED" | "SOLD_OUT" | "IMAGE_FAILED"
  mainImageKey: string | null
  mainImageUrl: string | null
  thumbnailImageKeys: string[]
  thumbnailImageUrls: string[]
  detailImageKeys: string[]
  detailImageUrls: string[]
  createdAt: string
}

export interface ProductListResponse {
  hasNext: boolean
  contents: ProductSummary[]
}

export interface ProductsParams {
  page?: number
  size?: number
  q?: string
  tag?: string
  artist?: string
}

export interface PresignedUploadResponse {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}

export interface ProductCreateRequest {
  sellerId: number
  name: string
  description: string
  height: string
  width: string
  shape: string
  material: string
  tags: string[]
  price: number
  mainImageKey: string
  detailImageKeys: string[]
}

export interface ProductCreateResponse {
  productId: number
  sellerId: number
  name: string
  description: string
  height: string
  width: string
  shape: string
  material: string
  tags: string[]
  price: number
  status: "IMAGE_PENDING" | "ACTIVE" | "IMAGE_FAILED"
  mainImageKey: string
  detailImageKeys: string[]
}

export type CartStatus = "ACTIVE" | "EXPIRED" | "DELETED"

export interface CartProductSummary {
  cartProductId: number
  productId: number
  createdAt: string
  updatedAt: string
}

export interface CartResponse {
  // 빈 장바구니(활성 카트 없음) 응답에서는 cartId가 0/누락일 수 있다.
  cartId?: number
  buyerId: number
  status: CartStatus
  expiresAt: string
  createdAt: string
  updatedAt: string
  products: CartProductSummary[]
}

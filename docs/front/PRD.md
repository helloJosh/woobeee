# PRD — front (프론트엔드)

`front`는 백엔드 API를 사용해 사용자 화면을 제공하는 Next.js 14 애플리케이션이다. 이 문서는 화면이 제공해야 할 **제품 목표·사용자 여정·범위**를 정의한다. 디렉터리 구조, API 호출/토큰 규칙, 스타일·컴포넌트 규칙 등 구현 기준은 [`../FRONTEND.md`](../FRONTEND.md)에 있다.

- 코드: `front/`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 방문자가 마켓플레이스 홈에서 상품을 탐색하고, 기술 블로그를 읽을 수 있다.
- 사용자가 Google 인증으로 구매자/판매자로 가입·로그인한다.
- 판매자가 이미지와 함께 상품을 등록한다.
- 인증 상태(토큰/역할)에 따라 화면과 동작이 달라진다.

## 사용자 여정

### 인증
- 로그인/회원가입 시작 화면에서 백엔드의 시작 API로 `authorizationUrl`을 받아 Google로 이동한다.
- `/auth/google/callback`에서 `code`/`state`를 `POST /api/auth/callback-google`로 교환해 토큰을 받는다(Authorization Code + PKCE).
- 회원가입은 유형 선택(`/signup`) → 구매자(`/signup/buyer`) / 판매자(`/signup/seller`)로 분기한다.
- 성공 후 기본 이동 위치는 마켓플레이스 홈(`/`).

### 상품 탐색 (홈)
- 통합 검색어(`q`), 작가(`artist`), 태그(`tag`)로 활성 상품을 탐색한다.
- 목록 이미지는 object key를 직접 조합하지 않고 API가 내려준 presigned URL을 사용한다.
- 상품 카드를 누르면 `/products/[productId]` 상세로 이동한다.

### 상품 상세 (`/products/[productId]`)
- `GET /api/products/{id}`로 단건을 조회한다. 상태와 무관하게 반환되므로 `RESERVED`(예약 중)도 조회·표시한다.
- 메인은 좌측 이미지 갤러리(대표+상세 이미지) / 우측 정보(제목·작가·가격·태그·상태 배지)로 구성한다.
- 아래에 상세 분류 표(작가·가격·크기·형태·재료·태그·등록일), 작품 설명, 상세 이미지를 표시한다.
- **장바구니 담기**(`cartId=0`)와 **바로 구매하기**를 제공한다. 바로 구매는 주문/결제 백엔드가 없어 현재 "준비 중" 안내만 한다.
- 담으면 해당 상품이 20분간 예약(`RESERVED`)된다는 안내를 노출하고, 예약 중인 상품은 담기를 비활성화한다.
- 비로그인 상태에서 담기를 누르면 `/login`으로 이동한다.
- 맨 아래에 **같은 작가의 다른 작품**을 목록 API(`artist` 필터, 현재 상품 제외)로 보여주고, 각 카드는 상세로 이동한다.

### 상품 등록 (판매자)
- `/products/new`에서 이미지 Presigned URL 발급 → 스토리지 직접 `PUT` 업로드 → temp image key로 `POST /api/products` 호출 순서로 등록한다.
- 텍스트 입력 draft는 `localStorage`에 1시간 TTL로 저장하고 등록 성공 시 삭제한다(파일 입력은 복원하지 않음).

### 장바구니 (구매자)
- 홈 상품 카드의 **담기** 버튼으로 상품을 장바구니에 담는다. 담기는 `cartId=0`(현재 활성 장바구니)으로 `POST /api/buyers/{buyerId}/carts/0`을 호출한다. 담기 버튼은 로그인한 비판매자에게만 노출한다.
- `/cart`에서 `GET /api/buyers/{buyerId}/carts/0`으로 현재 장바구니를 조회한다. 카트 응답에는 `productId`만 있으므로 각 항목을 `GET /api/products/{id}`로 보강해 이름·가격·이미지를 표시한다.
- 예약 락 만료(20분)까지 남은 시간을 카운트다운으로 보여주고, 만료되면 장바구니를 재조회한다.
- 개별 상품 삭제(`DELETE .../products/{productId}`)와 전체 비우기(`DELETE .../carts/{cartId}`, 확인 다이얼로그)를 제공한다.
- 담기 충돌(409, 다른 장바구니 예약 중)·담을 수 없는 상태(400)·미존재(404)는 `apiRequest`의 안내(alert)로 처리한다. 상세 규칙은 [`../cart/PRD.md`](../cart/PRD.md).

### 블로그
- `/blog` 목록, `/blog/[postId]` 상세를 제공한다.
- 요청에 언어 정보(`ko-KR`/`en-US`)를 포함해 다국어 콘텐츠를 표시한다([`../blog/adr/ADR-002-i18n.md`](../blog/adr/ADR-002-i18n.md)).

## 인증 상태 처리

- access/refresh token, `memberId`, `role`을 `localStorage`에 저장한다.
- `role`로 판매자 전용 UI(상품 등록 등) 노출을 제어한다([`../auth/adr/ADR-003-member.md`](../auth/adr/ADR-003-member.md)).
- 인증 만료 시 `POST /api/auth/refresh-tokens`로 재발급을 시도하고, 실패하면 토큰을 제거한다.

## 범위와 제약

- 현재 화면 범위: 홈/상품 탐색, 상품 등록, 로그인·회원가입·로그아웃, 블로그 목록/상세, Google callback, (작업 중) chat.
- 다크/라이트 모드 전환(`next-themes`)을 제공한다.
- `API_BASE_URL`은 `NEXT_PUBLIC_API_BASE_URL`이 없으면 `http://localhost:8000`.
- 빌드 검증은 `cd front && npm run build`. `next/font`의 Google Fonts 조회로 네트워크 차단 시 빌드가 실패할 수 있다.

## 비기능 요구사항

- API 응답은 백엔드의 `ApiResponse` 규약(`header.isSuccessful`/`message`)을 기준으로 성공/실패를 처리한다([`../_common/adr/ADR-002-apiresponse.md`](../_common/adr/ADR-002-apiresponse.md)).
- 공통 호출 로직은 `lib/api.ts`, 타입은 `lib/types.ts`로 모은다.
- UI는 `components/ui/`의 기존 컴포넌트를 우선 재사용한다.

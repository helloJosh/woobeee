# ARCHITECTURE

> **2026-07-31 재구성:** 단일 모듈 모놀리스(`art-market-place`)에서 Maven 멀티모듈
> (`woobeee`)로 전환했다. product/cart는 폐기했고 game(WebFlux)을 추가했다. 아래 모듈 구조가
> 우선하며, 이후 절의 도메인 설명은 auth/blog에 대해서만 유효하다.

## 모듈 구조

```text
                    Next.js (front :3000)
        /api/auth/* /api/back/*      /api/game/*
                    │                     │
               [app-mvc :8000]     [app-webflux :8001]
               Tomcat / JPA         Netty / R2DBC
                    │                     │
                    └──────── core ───────┘
              (ApiResponse · 토큰 계약 · Redis 토큰스토어)
                    │                     │
            ┌───────┴─────────────────────┴───────┐
       PostgreSQL :9432 (공유)          Redis :9379 (공유 토큰)
            └──── Flyway가 스키마 단일 관리 (app-mvc 소유) ────┘
```

| 모듈 | 스택 | 책임 | 의존 |
| --- | --- | --- | --- |
| `core` | 순수 라이브러리 (웹 스택 무의존) | `ApiResponse`, 토큰 계약(`AuthTokenType`/`TokenMetadata`), `RedisTokenStore` | — |
| `app-mvc` | Boot + `starter-webmvc` + JPA | auth(토큰 발급·로그인·OAuth) + blog, Flyway 소유 | core |
| `app-webflux` | Boot + `starter-webflux` + R2DBC | game(골격), Redis 토큰 **검증만** | core |
| `front` | Next.js 14 | rewrites로 두 백엔드 프록시 | — |

- 논리적으로 하나의 앱(회원·DB·Redis 공유), 물리적으로 Boot 프로세스 2개(Netty와 Tomcat은 한
  JVM에 공존 불가).
- 토큰 공유: app-mvc(auth)가 발급 → Redis에 `auth:token:access:<token>` 해시로 저장 →
  app-webflux가 `ReactiveTokenVerifier`로 같은 키를 직접 읽어 검증. 앱 간 HTTP 호출 없음.
- 스키마: Flyway가 단일 소스(`app-mvc/src/main/resources/db/migration/`). JPA는 `validate`
  전용이고 app-webflux는 `flyway.enabled=false`.

## 이전 구조 (art-market-place 기준 — 이하 절은 auth/blog 흐름 참고용)

`art-market-place`는 Spring Boot 백엔드와 Next.js 프론트엔드를 같은 레포지토리에 둔 단일 저장소 구조다.

## 상위 구조

```text
art-market-place
|-- src/                  Spring Boot 백엔드
|-- front/                Next.js 프론트엔드
|-- docs/                 제품/아키텍처/기술결정 문서
|-- .claude/              project 범위 claude 스킬, 설정
|-- .docker-compose/      로컬 PostgreSQL, Redis, Kafka, MinIO 구성
`-- pom.xml               백엔드 Maven 설정
```

## 백엔드 모듈

### `auth`

회원가입, 로그인, Google OAuth callback, 토큰 발급/재발급, 회원 프로필과 프로필 이미지를 담당한다.

- Controller: `AuthController`, `TokenGenerateController`, `MemberProfileImageController`
- Service: `AuthService`, `TokenService`, `MemberProfileImageService`, Google OAuth 관련 client/store
- Entity: `Member`(단일 회원, `members`), `Address`
- Repository: `MemberRepository`
- Token: `TokenGenerator`, `TokenStore`, Redis 기반 구현. 역할은 `ROLE_MEMBER` 하나다.
- Storage: 프로필 이미지는 `_common/storage`의 `S3Client`/`S3Presigner`를 주입받아 presigned PUT/GET으로 다룬다.

### `product`

상품 목록 조회, 상품 등록과 상품 이미지 업로드/복구를 담당한다.

- Controller: `ProductController`
- Service: `ProductService`, `ProductImageStorageService`
- Repository: 단순 CRUD는 Spring Data JPA, 커스텀 목록 조회는 로우 쿼리(Native SQL, 기존 QueryDSL fragment는 마이그레이션 대상)
- Entity: `Product`, `ProductImage`, `ProductTag`, `Tag`
- Event: 이미지 등록 후 스토리지 이동 처리를 위한 이벤트 리스너
- Storage: S3 호환 스토리지 설정과 Presigned URL 생성

### `cart`

구매자(회원) 및 게스트(비회원) 장바구니, 장바구니 상품 추가/삭제, 상품 단위 임시 락을 담당한다.

- Controller: `CartController`(회원 `/api/buyers/{buyerId}/carts/{cartId}`), `GuestCartController`(게스트 `/api/guest/carts`, `Guest-Token` 헤더)
- Service: `CartService`(소유자 추상화 `CartOwner` = `BuyerOwner`|`GuestOwner` 기준 동작), `CartMergeService`(회원 전환 시 병합)
- Repository: `CartRepository`, `CartProductRepository`
- Entity: `Cart`(`buyerId` nullable + `guestToken`), `CartProduct`(`buyerId` nullable), `CartStatus`
- Event: `MemberAuthenticatedEvent{buyerId, guestToken}`(cart 소유) — auth가 발행, `CartMergeService`가 수신
- Reservation: 장바구니 추가 성공 시 상품 상태를 `RESERVED`로 바꾸고, 활성 장바구니의 `expiresAt`을 기준으로 20분 예약을 관리한다. 회원/게스트 동일.
- 상세: `docs/cart/adr/ADR-001-usercart.md`(예약 락), `docs/cart/adr/ADR-002-guestcart.md`(게스트 소유 모델·병합·이벤트).

### `blog`

게시글, 카테고리, 댓글, 좋아요 기능을 담당한다.

- Controller: `PostController`, `CategoryController`, `CommentController`, `LikeController`
- Service: 각 도메인별 interface/implementation 분리
- Repository: 단순 CRUD는 Spring Data JPA, 검색/집계 조회는 로우 쿼리(Native SQL, 기존 QueryDSL fragment는 마이그레이션 대상)
- Entity: `Post`, `Category`, `Comment`, `Like`
- Support: 페이징, Redis 지원, 업로드 진행 스트림

### `_common`

공통 요청 처리와 필터를 둔다.

- `AccessTokenLoginIdHeaderFilter`: access token 기반 loginId 헤더 주입
- `MutableHttpServletRequest`: 요청 헤더 조작을 위한 wrapper
- `CorsConfig`: `/api/**` 요청에 대해 로컬 프론트 개발 서버와 운영 도메인의 CORS를 허용
- `QuerydslConfig`: 기존 QueryDSL 조회용 `JPAQueryFactory` 제공. 로우 쿼리 마이그레이션 완료 후 제거 예정(ADR-001).

## 데이터 저장소

- PostgreSQL: JPA 영속성 저장소
- Redis: 토큰, OAuth state, 세션/캐시성 데이터
- S3/MinIO: 상품 이미지와 블로그 첨부 파일 저장소

## 쿼리 구현 규칙

- 단순 조회는 Spring Data JPA 파생 메서드로 유지한다.
- 그 외 모든 커스텀 조회(동적 조건, 검색, 집계, 조인/서브쿼리, 목록)는 로우 쿼리(Native SQL)로 작성한다. QueryDSL은 더 이상 사용하지 않으며 기존 fragment는 마이그레이션 대상이다.
- 로우 쿼리는 N+1을 해결한 형태(조인 일괄 조회 또는 식별자 배치 IN 조회)로 작성하고, 값은 바인딩 파라미터로만 주입한다.
- 상세 정책: `docs/_global/adr/ADR-001-postgresql.md`.

## 주요 흐름

### 인증 흐름

1. 클라이언트가 회원가입 또는 로그인 authorization 생성을 요청한다.
2. 서버가 Google authorization URL과 state를 발급한다.
3. Google callback에서 code/state를 검증한다.
4. 서버가 회원 정보를 확인하고 access/refresh token을 발급한다.
5. refresh token은 device, IP 정보와 함께 저장소에서 검증한다.

### 상품 이미지 흐름

1. 클라이언트가 이미지 업로드용 Presigned URL을 요청한다.
2. 클라이언트가 temp 경로에 직접 업로드한다.
3. 상품 등록 시 temp image key를 함께 전달한다.
4. 트랜잭션 커밋 이후 이미지가 상품 경로로 이동된다.
5. 실패한 이미지 이동은 복구 API로 재시도한다.

### 상품 목록 흐름

1. 클라이언트가 `GET /api/products`로 page, size, q, artist, tag 조건을 전달한다.
2. 서버는 active 상품만 최신순으로 조회한다.
3. q 조건은 상품명, 상품설명, 상품 치수/형태/재료, 판매자 nickname, 태그명 기준으로 통합 검색한다.
4. artist 조건은 판매자 nickname 기준으로 필터링하고, tag 조건은 태그명 기준으로 필터링한다.
5. 상품 이미지, 태그, 판매자 nickname을 batch 조회해 목록 응답으로 조합한다.
6. 이미지 object key는 보관용으로 유지하고, 브라우저 렌더링에는 S3/MinIO presigned download URL을 내려준다.

### 장바구니 흐름

1. 클라이언트가 `POST /api/buyers/{buyerId}/carts/{cartId}`와 `productId`로 장바구니 상품 추가를 요청한다.
2. `cartId`가 `0`이면 구매자의 활성 장바구니를 재사용하거나 새 장바구니를 생성한다.
3. 서버는 구매자와 활성 상품 존재 여부를 확인한다.
4. 동일 상품이 다른 활성 장바구니에 있고 해당 장바구니 `expiresAt`이 현재 시각 이후이면 `409 Conflict`로 거절한다.
5. 추가 또는 조회에 성공한 활성 장바구니는 `expiresAt`을 현재 시각 기준 20분 뒤로 연장한다.
6. 상품 추가 성공 시 `products.status`를 `RESERVED`로 바꿔 재고 1개 상품을 다른 사용자가 구매하지 못하게 한다.
7. 장바구니 또는 장바구니 상품 삭제 시 관련 `cart_product` 행을 삭제하고 상품 상태를 `ACTIVE`로 복구한다.
8. 만료된 장바구니는 `EXPIRED`로 바꾸고 담긴 `RESERVED` 상품을 `ACTIVE`로 복구한다.

### 게스트 장바구니 흐름

1. 비회원은 `/api/guest/carts`(`Guest-Token` 헤더)로 장바구니를 사용한다. 서비스는 `CartOwner`(`GuestOwner`) 기준으로 회원과 동일한 락/만료/충돌 로직을 공유한다.
2. 첫 담기에서 `Guest-Token`이 없으면 서버가 `TokenGenerator`로 불투명 토큰을 발급하고 게스트 카트(`buyer_id=null`, `guest_token`)를 생성해 응답 `guestToken`으로 반환한다.
3. 이후 요청은 같은 토큰의 최신 활성 카트를 재사용한다. 락 충돌 판정은 `cartId` 기준이라 회원/게스트 카트를 가리지 않는다.

### 회원 전환 시 카트 병합 흐름 (cart ↔ auth 이벤트)

1. 게스트가 BUYER로 로그인/회원가입할 때 `guestCartToken`을 함께 전달하면, auth는 토큰을 `GoogleAuthorizationContext`에 적재해 OAuth 콜백까지 유지한다.
2. `auth.AuthService`는 BUYER 세션 발급 직후 토큰이 non-blank이면 `MemberAuthenticatedEvent{buyerId, guestToken}`를 발행한다. SELLER 분기는 발행하지 않는다.
3. `cart.CartMergeService`가 **동기** 리스너로 발행 트랜잭션 안에서 병합을 수행해 로그인과 병합을 원자적으로 처리한다. 회원 카트가 없으면 게스트 카트를 re-own, 있으면 union(중복 1개 유지, 게스트 카트 `EXPIRED`, 회원 카트 락 연장)한다.
4. 도메인 경계: 의존은 **auth → cart 단방향**이며 Spring `ApplicationEvent`로만 연결된다. 이벤트 타입은 cart가 소유하고 auth는 그 타입과 `ApplicationEventPublisher`만 import한다(cart의 service/repo/entity 미사용). cart → auth 기존 `BuyerRepository` 의존은 유지된다.

### 블로그 흐름

1. 게시글은 카테고리, 검색어, 페이징 조건으로 조회한다.
2. 상세 조회는 locale과 로그인 정보를 반영한다.
3. 댓글은 댓글/대댓글 구조를 지원한다.
4. 좋아요는 로그인 사용자 기준으로 등록/취소한다.

## 검증과 안전장치

- `spring.jpa.properties.hibernate.hbm2ddl.auto=validate`를 기본으로 사용한다.
- Harness 실행 시 `.codex/settings.json`의 validation 명령을 강제 실행한다.
- `SchemaValidationTest`는 JPA 매핑과 실제 PostgreSQL 스키마 불일치를 감지하기 위한 전용 테스트다.
- 엔티티 변경은 DB 스키마 변경으로 간주하며, 문서와 마이그레이션 계획을 함께 갱신해야 한다.

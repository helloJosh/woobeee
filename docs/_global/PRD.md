# PRD — 전체 개요 (Global)

`art-market-place`는 예술 작품과 콘텐츠를 중심으로 **회원·상품·장바구니·기술 블로그** 기능을 제공하는 웹 서비스다. 이 문서는 서비스 전체의 목표·사용자·범위를 정의하고, 세부 기능 요구사항은 각 도메인 PRD로 위임한다.

> 도메인별 상세: [`auth/PRD.md`](../auth/PRD.md), [`product/PRD.md`](../product/PRD.md), [`cart/PRD.md`](../cart/PRD.md), [`blog/PRD.md`](../blog/PRD.md)

## 목표

- 구매자와 판매자가 Google OAuth 기반 흐름으로 가입/로그인하고, access/refresh token으로 인증 상태를 유지한다.
- 판매자가 상품을 등록하고, 이미지를 S3 호환 스토리지에 Presigned URL로 직접 업로드한다.
- 구매자가 재고 1개 단위 상품을 장바구니에 담는 동안 다른 구매자가 동일 상품을 선점하지 못하도록 임시 예약 락을 건다.
- 사용자가 기술 블로그 게시글을 조회하고 댓글·좋아요로 상호작용한다.
- 프론트엔드(Next.js)가 위 기능을 위한 화면(로그인/회원가입, 상품 목록·등록, 블로그 목록·상세)을 제공한다.

## 사용자

- **비회원**: 블로그 게시글과 공개 상품 목록을 조회한다.
- **구매자(Buyer)**: 로그인 후 상품을 장바구니에 담고 블로그에서 상호작용한다.
- **판매자(Seller)**: 로그인 후 상품을 등록·관리한다.
- **운영/개발자**: 문서와 Harness 검증 기준으로 변경을 검토한다.

## 기능 범위 (도메인 단위)

| 도메인 | 범위 | 베이스 경로 |
| --- | --- | --- |
| `auth` | 구매자/판매자 회원가입, Google OAuth 로그인, access/refresh token 발급·재발급 | `/api/auth` |
| `product` | 상품 목록 조회(통합 검색·필터), 상품 등록, 이미지 Presigned 업로드·복구 | `/api/products` |
| `cart` | 장바구니 조회/추가/삭제, 상품 20분 예약 락 | `/api/buyers/{buyerId}/carts/{cartId}` |
| `blog` | 게시글·카테고리·댓글·좋아요 | `/api/back/...` |

## 비기능 요구사항

- **스키마 정합성**: JPA 매핑과 PostgreSQL 스키마 불일치를 `SchemaValidationTest`와 Hibernate `validate`로 검출한다.
- **저장소 분리**: 영속 데이터는 PostgreSQL, 토큰·OAuth state·세션은 Redis, 파일은 S3/MinIO를 사용한다.
- **업로드 방식**: 대용량 이미지는 서버 중계 대신 Presigned URL 기반 클라이언트 직접 업로드를 우선한다.
- **쿼리 규칙**: 단순 조회는 Spring Data JPA 파생 메서드, 그 외 커스텀 조회는 로우 쿼리(Native SQL)로 N+1을 해결해 작성한다. QueryDSL은 폐기(마이그레이션 대상). 상세: [`adr/ADR-001-postgresql.md`](adr/ADR-001-postgresql.md).
- **API 응답 형식**: 도메인별 `ApiResponse` 래퍼 형식을 유지한다.
- **검증**: 변경은 Maven 테스트와 프론트 빌드로 검증한다.

## 현재 제약

- 로컬 검증은 PostgreSQL·Redis·MinIO 등 외부 서비스 기동 상태에 의존한다.
- 프론트 빌드는 `next/font`의 Google Fonts 조회로 네트워크 상태에 영향을 받는다.
- Kafka는 로컬 인프라(`.docker-compose`)에 구성되어 있으나 현재 백엔드 코드에는 연동되어 있지 않다(향후 이벤트 스트리밍 대비). [`adr/ADR-003-kafka.md`](adr/ADR-003-kafka.md) 참조.

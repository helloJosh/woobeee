# PRD — auth (인증/회원)

`auth` 도메인은 구매자/판매자 회원, Google OAuth 기반 로그인, access/refresh token 발급·재발급을 담당한다.

- 베이스 경로: `/api/auth`
- 코드: `com.woobeee.artmarketplace.auth`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 구매자(Buyer)와 판매자(Seller)가 Google OAuth로 가입·로그인한다.
- 로그인 후 access/refresh token으로 인증 상태를 유지하고, refresh로 재발급한다.
- 토큰은 발급 시점의 디바이스/IP 정보와 함께 관리한다.

## 회원 모델

- `Buyer`, `Seller` 엔티티와 공통 `Address`, 회원 구분 `MemberType`.
- 활성 상태 여부(`isActive`)로 가입 완료/이용 가능 회원을 구분한다.
- 저장소: `BuyerRepository`, `SellerRepository` (Spring Data JPA).

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 |
| --- | --- |
| 구매자 회원가입 authorization 시작 | `POST /api/auth/signup/buyers` |
| 판매자 회원가입 authorization 시작 | `POST /api/auth/signup/sellers` |
| 로그인 authorization 시작 | `POST /api/auth/login` |
| Google callback 처리 | `POST /api/auth/callback-google` |
| access token 발급 | `POST /api/auth/access-tokens` |
| refresh token 재발급 | `POST /api/auth/refresh-tokens` |

## 인증 흐름

1. 클라이언트가 회원가입 또는 로그인 authorization 생성을 요청한다.
2. 서버가 Google authorization URL과 state를 발급한다. state는 Redis에 TTL(기본 600초)과 함께 저장된다(`RedisGoogleAuthorizationStateStore`).
3. Google callback에서 code/state를 검증한다(`GoogleOauthClient`, `GoogleIdentityVerifier`).
4. 회원 정보를 확인/생성하고 access/refresh token을 발급한다(`TokenService`).
5. 토큰은 `TokenMetadata`(memberId, role, device, ip)와 함께 Redis(`RedisTokenStore`)에 저장된다.

## 토큰 정책

- access/refresh token은 UUID 기반(`UuidTokenGenerator`)으로 생성한다.
- 타입별 TTL은 `AuthTokenType`이 정의한다.
- **refresh 재발급 시 디바이스 일치를 검증**한다. role 없음/디바이스 불일치/만료는 `401 Unauthorized`로 거절한다. IP 불일치는 현재 경고 후속 처리(TODO)로 두고 거절하지 않는다. 상세: [`adr/ADR-001-authdevice.md`](adr/ADR-001-authdevice.md).
- 재발급에 성공하면 사용한 refresh token은 즉시 삭제(rotation)한다.

## 인수 기준 (Acceptance Criteria)

각 항목은 테스트로 커버한다(프로세스 규칙은 `CLAUDE.md`). 동작/계약 변경 시 이 표를 먼저 갱신하고 테스트를 함께 수정한다.

| ID | 인수 기준 (Given–When–Then) | 커버 테스트 |
| --- | --- | --- |
| AUTH-AC-01 | 토큰 발급 시 access는 15분, refresh는 30일 TTL로 저장한다 | `TokenServiceTest` |
| AUTH-AC-02 | 유효하지 않거나 만료(잔여 TTL ≤ 0)된 refresh token으로 재발급하면 `401`을 반환한다 | `TokenServiceTest` |
| AUTH-AC-03 | 저장된 `role`이 비어 있으면 재발급은 `401`을 반환한다 | `TokenServiceTest` |
| AUTH-AC-04 | 요청 `device`가 저장된 `device`와 다르면 재발급은 `401`을 반환한다 | `TokenServiceTest` |
| AUTH-AC-05 | 재발급에 성공하면 새 access/refresh를 발급하고 사용한 refresh token을 삭제(rotation)한다 | `TokenServiceTest` |
| AUTH-AC-06 | `ip`가 달라도 device가 일치하면 재발급은 거절하지 않고 진행한다(현재 정책, IP 경고는 TODO) | `TokenServiceTest` |
| AUTH-AC-07 | Google callback에서 state/code 검증에 실패하면 인증을 거절한다 | `AuthServiceTest` |
| AUTH-AC-08 | 구매자/판매자 회원가입이 성공하면 해당 엔티티를 `active=true`로 생성한다 | `AuthServiceTest` |

## 설정

- `oauth.google.*` (`application.yaml`): client-id/secret, redirect-uri, authorization-uri, token-uri, scope, state TTL, 타임아웃. (`GoogleOauthProperties`)
- 시크릿은 환경변수(`GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`)로 주입한다.

## 비기능 요구사항

- 토큰·OAuth state는 Redis 저장소를 사용한다([`../_global/adr/ADR-002-redis.md`](../_global/adr/ADR-002-redis.md)).
- 인증 실패는 도메인 advice(`AuthRestControllerAdvice`)로 일관된 응답을 반환한다.
- access token 기반 loginId는 `_common`의 `AccessTokenLoginIdHeaderFilter`가 헤더로 주입한다.

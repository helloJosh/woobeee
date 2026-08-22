# PRD — auth (인증/회원)

`auth` 도메인은 회원, Google OAuth 기반 로그인, access/refresh token 발급·재발급, 회원 프로필(프로필 이미지 포함)을 담당한다.

- 베이스 경로: `/api/auth`
- 코드: `com.woobeee.mvc.auth`
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 회원이 Google OAuth로 가입·로그인한다.
- 로그인 후 access/refresh token으로 인증 상태를 유지하고, refresh로 재발급한다.
- 토큰은 발급 시점의 디바이스/IP 정보와 함께 관리한다.
- 회원이 프로필 이미지를 등록·교체·삭제한다.

## 회원 모델

- 단일 `Member` 엔티티(`members` 테이블)와 공통 `Address`. 구매자/판매자 구분은 없다.
- 필드: `id`, `googleSubject`, `email`, `nickname`, `termsAgreed`, `privacyPolicyAgreed`,
  `profileImageKey`(nullable), `gameMoney`(long, 기본 0), `role`(`ROLE_MEMBER`/`ROLE_ADMIN`,
  기본 `ROLE_MEMBER`), `active`, `createdAt`.
- 활성 상태 여부(`isActive`)로 가입 완료/이용 가능 회원을 구분한다.
- 역할은 `MemberRole`(`ROLE_MEMBER`/`ROLE_ADMIN`)로 저장한다. 회원가입은 항상 `ROLE_MEMBER`로
  생성하고, `ROLE_ADMIN` 지정은 아직 DB 수동 UPDATE 경로뿐이다(`members_role_check` CHECK
  제약이 오타를 막는다). Redis 토큰의 `role` 값은 회원 role의 enum 이름 그대로다 — 아직 분기에
  쓰이지 않는다. 설계: [`../superpowers/specs/2026-08-05-member-role-design.md`](../superpowers/specs/2026-08-05-member-role-design.md).
- `gameMoney`는 가입 시 0으로 생성하고 조회만 한다. 증감 경로는 게임 spec에서 정한다.
- 저장소: `MemberRepository` (Spring Data JPA).
- `members` 는 `app-webflux` 도 R2DBC 로 **읽는다**(게임 참가자 닉네임). 쓰기는 app-mvc 단독이다.

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 |
| --- | --- |
| 회원가입 authorization 시작 | `POST /api/auth/signup` |
| 로그인 authorization 시작 | `POST /api/auth/login` |
| Google callback 처리 | `POST /api/auth/callback-google` |
| access token 발급 | `POST /api/auth/access-tokens` |
| refresh token 재발급 | `POST /api/auth/refresh-tokens` |
| 내 프로필 조회 | `GET /api/auth/me` |
| 프로필 이미지 업로드 URL 발급 | `POST /api/auth/me/profile-image/presigned-url` |
| 프로필 이미지 등록/교체 | `PUT /api/auth/me/profile-image` |
| 프로필 이미지 삭제 | `DELETE /api/auth/me/profile-image` |

## 프로필 이미지

- 업로드는 presigned PUT 2-step이다. 파일 바이트는 앱을 경유하지 않는다.
- 키 규칙은 `profiles/{memberId}/{uuid}/{sanitized-filename}` 이고, `memberId`는 요청 본문이 아니라
  **서버가 토큰에서** 결정한다. temp 경유는 없다.
- 발급은 contentType 화이트리스트(`image/png`, `image/jpeg`, `image/webp`, `image/gif`)를 강제한다.
  그 밖은 `400`.
- 등록은 fileKey가 `profiles/{요청자 memberId}/`로 시작하는지 검증한다. 그 밖은 `403`.
- 등록/교체는 컬럼을 먼저 커밋하고 그다음 이전 오브젝트를 삭제한다. 삭제가 실패해도 프로필은
  정상이며 고아 오브젝트만 남는다(로그 경고).
- 조회는 presigned GET URL이며, 프로필 미설정이면 `null`이다.
- 인증은 `AccessTokenLoginIdHeaderFilter`가 주입하는 `loginId` 헤더로 하고, 없으면 `401`이다.

## 인증 흐름

1. 클라이언트가 회원가입 또는 로그인 authorization 생성을 요청한다.
2. 서버가 Google authorization URL과 state를 발급한다. state는 Redis에 TTL(기본 600초)과 함께 저장된다(`RedisGoogleAuthorizationStateStore`).
3. Google callback에서 code/state를 검증한다(`GoogleOauthClient`, `GoogleIdentityVerifier`).
4. 회원 정보를 확인/생성하고 access/refresh token을 발급한다(`TokenService`). 로그인은 google subject로
   회원을 찾고, 미등록이면 `404`다.
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
| AUTH-AC-08 | 회원가입이 성공하면 `members`에 `active=true`, `gameMoney=0`으로 생성한다 | `AuthServiceTest` |
| AUTH-AC-09 | 로그인은 memberType 없이 google subject로 회원을 찾고, 미등록이면 `404`를 반환한다 | `AuthServiceTest` |
| AUTH-AC-10 | `POST /api/auth/me/profile-image` 는 multipart `file` 을 `profiles/{토큰의 memberId}/{uuid}/{파일명}` 키로 저장한다 — memberId 는 요청이 아니라 토큰에서 온다 | `MemberProfileImageServiceTest`, `MemberProfileImageControllerTest` |
| AUTH-AC-11 | 허용 목록(png/jpeg/webp/gif) 밖 contentType 으로 업로드하면 `400` 이고 스토리지에 손대지 않는다 | `MemberProfileImageServiceTest` |
| AUTH-AC-12 | 업로드는 5MB 를 넘으면 `400`, 정확히 5MB 는 통과한다. 빈 파일도 `400` — 업로드가 앱을 거치므로 상한이 없으면 큰 파일이 앱 힙을 받는다 | `MemberProfileImageServiceTest` |
| AUTH-AC-13 | 교체가 성공하면 컬럼을 갱신하고 이전 오브젝트를 삭제한다. 삭제가 실패해도 교체는 성공으로 남는다(고아 오브젝트만 생긴다) | `MemberProfileImageServiceTest` |
| AUTH-AC-14 | `GET /me` 는 이미지 URL 이 아니라 `hasProfileImage` 를 반환한다 — `<img>` 는 Authorization 을 못 보내므로 URL 을 주면 쓸 수 없는 값이 된다 | `MemberProfileImageServiceTest`, `MemberProfileImageControllerTest` |
| AUTH-AC-15 | 회원가입으로 생성된 회원의 role은 `ROLE_MEMBER`다 | `AuthServiceTest` |
| AUTH-AC-16 | 토큰의 role은 회원의 role에서 파생된다 — `ROLE_ADMIN` 회원이 로그인하면 `ROLE_ADMIN`으로 발급한다 | `AuthServiceTest` |
| AUTH-AC-17 | `ROLE_ADMIN` 의 access token 은 1일 TTL 로, 그 외 role 은 기본 15분으로 발급한다. `TokenResponse` 의 만료 초도 실제 TTL 을 반영한다 (긴 글 작성 중 만료 방지 — 사용자 결정) | `TokenServiceTest` |
| AUTH-AC-18 | `GET /api/auth/me/profile-image` 는 오브젝트 바이트와 저장된 contentType 을 `ApiResponse` 봉투 <b>밖으로</b> 반환하고 `Cache-Control: no-store` 를 붙인다 — 봉투에 실으면 blob 이 읽을 수 없는 JSON 이 되고, 본인 전용 리소스가 공유 캐시에 남으면 안 된다 | `MemberProfileImageServiceTest`, `MemberProfileImageControllerTest` |
| AUTH-AC-19 | 프로필 이미지를 설정하지 않았거나 컬럼만 남고 오브젝트가 없으면 `404` 다 — 후자에서 500 이 나면 삭제가 반쯤 실패한 계정의 화면이 무너진다 | `MemberProfileImageServiceTest`, `MemberProfileImageControllerTest` |

## 설정

- `oauth.google.*` (`application.yaml`): client-id/secret, redirect-uri, authorization-uri, token-uri, scope, state TTL, 타임아웃. (`GoogleOauthProperties`)
- `storage.s3.*` (`application.yaml`): 프로필 이미지 presign에 쓰는 버킷·엔드포인트·자격증명·TTL. (`StorageProperties`)
- 시크릿은 환경변수(`GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`)로 주입한다.

## 비기능 요구사항

- 토큰·OAuth state는 Redis 저장소를 사용한다([`../_global/adr/ADR-002-redis.md`](../_global/adr/ADR-002-redis.md)).
- 인증 실패는 도메인 advice(`AuthRestControllerAdvice`)로 일관된 응답을 반환한다.
- access token 기반 loginId는 `_common`의 `AccessTokenLoginIdHeaderFilter`가 헤더로 주입한다.

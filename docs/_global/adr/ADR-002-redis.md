# ADR-002. 토큰·OAuth state·세션 저장소로 Redis를 사용한다

- 상태: 적용
- 범위: 전역(Global)

## 맥락

access/refresh token과 Google OAuth authorization state는 TTL 만료와 빠른 조회가 필요하고, PostgreSQL 영속 모델에 둘 필요가 없다. 세션도 다중 인스턴스에서 공유할 수 있어야 한다.

## 결정

휘발성·TTL 기반 데이터는 **Redis**에 저장한다.

- **토큰**: `RedisTokenStore`가 access/refresh token을 `TokenMetadata`(memberId, role, device, ip)와 함께 TTL을 두고 저장한다. 토큰 타입별 TTL은 `AuthTokenType`이 정의한다. (`auth/token`)
- **OAuth state**: `RedisGoogleAuthorizationStateStore`가 authorization state를 TTL과 함께 저장한다. TTL은 `oauth.google.authorization-state-ttl-seconds`(기본 600초). (`auth/service`)
- **세션**: `spring-session-data-redis`로 세션을 Redis에 둔다.
- 접속 설정은 `application.yaml`의 `spring.data.redis`(host, port, password).

## 영향

- 로컬/테스트 환경에서 Redis 의존성이 생긴다 (`.docker-compose`의 `redis` 서비스).
- Redis 장애 시 인증·토큰 흐름이 실패하므로, 실패 시 에러 메시지를 명확히 유지한다.

## 관련

- 인증 흐름 전반은 [`auth/PRD.md`](../../auth/PRD.md), refresh token의 device 바인딩은 [`auth/adr/ADR-001-authdevice.md`](../../auth/adr/ADR-001-authdevice.md).

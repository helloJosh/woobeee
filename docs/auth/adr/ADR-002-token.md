# ADR-002. 인증 토큰은 불투명(opaque) UUID 토큰 + Redis 저장 방식을 사용한다 (JWT 대신)

- 상태: 적용
- 범위: auth

## 맥락

access/refresh token이 필요하다. 선택지는 (a) 서명 기반 stateless JWT와 (b) 서버가 저장소에서 관리하는 불투명 토큰이다. 본 서비스는 토큰 즉시 무효화(로그아웃/회전), 디바이스 바인딩, 만료 관리가 중요하고 이미 Redis를 운영한다.

## 결정

**불투명 UUID 토큰**을 사용하고 상태는 Redis에 저장한다.

- 토큰 값은 `UuidTokenGenerator`가 `UUID.randomUUID()`로 생성한다(서명/클레임 없음).
- 저장은 `RedisTokenStore`가 담당한다:
  - 토큰 키 `auth:token:{type}:{token}` → 해시(`memberId`, `role`, `device`, `ip`)에 TTL 부여.
  - 역방향 키 `auth:user-token:{type}:{memberId}:{device}` → 현재 유효 토큰 값을 같은 TTL로 저장. (memberId+device 단위로 현재 토큰을 추적)
- TTL은 `AuthTokenType`이 정의: **ACCESS 15분, REFRESH 30일**.
- 검증은 Redis 조회로 수행하며, 잔여 TTL이 0 이하이면 무효로 본다.
- 재발급 시 기존 refresh 토큰을 삭제(회전)하고, 삭제 시 역방향 키가 현재 토큰과 같을 때만 함께 제거한다.

## 근거

- 서버가 토큰 상태를 갖고 있어 **즉시 무효화/회전**이 가능하다(JWT의 stateless 특성으로는 어렵다).
- `TokenMetadata`(device/ip)를 함께 저장해 디바이스 바인딩 검증을 지원한다([`ADR-001-authdevice.md`](ADR-001-authdevice.md)).
- 별도 키 인프라(서명 키 회전 등) 없이 Redis만으로 운영한다([`../../_global/adr/ADR-002-redis.md`](../../_global/adr/ADR-002-redis.md)).

## 트레이드오프 / 한계

- 모든 인증 검증이 Redis 조회를 동반한다(네트워크 1회). 대신 무효화 제어를 얻는다.
- Redis 장애 시 인증이 실패하므로 가용성을 신경 써야 한다.
- 토큰 자체에 정보가 없으므로 다른 서비스가 자체 검증할 수 없다(중앙 저장소 조회 필요).

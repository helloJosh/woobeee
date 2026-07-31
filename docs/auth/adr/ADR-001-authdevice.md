# ADR-001. refresh token을 발급 시 디바이스에 바인딩하고 재발급 시 검증한다

- 상태: 적용
- 범위: auth

## 맥락

refresh token이 탈취되면 임의의 클라이언트에서 access token을 무한 재발급할 수 있다. 발급 맥락(디바이스/IP)을 토큰 메타데이터로 묶어 재발급을 제한하면 탈취 토큰의 오용 범위를 줄일 수 있다.

## 결정

- 토큰 발급 시 `TokenMetadata(memberId, role, device, ip)`를 함께 Redis에 저장한다(`RedisTokenStore`).
- refresh 재발급(`TokenService.refresh`) 시 다음을 검증한다:
  - 저장된 `role`이 비어 있으면 `401`.
  - 저장된 `device`가 없거나 요청 `device`와 다르면 `401` ("Device does not match").
  - 잔여 TTL이 0 이하이면 `401` ("Refresh token expired").
- 검증 통과 시 새 access/refresh token을 발급하고 **기존 refresh token은 삭제**한다(토큰 회전, rotation).

## IP에 대한 처리

- IP 불일치는 현재 거절하지 않는다. `TokenService.validateRefreshMetadata`에 `// TODO : IP가 다를시 경고 알림 기능 추가`로 후속 과제만 표시되어 있다.
- 즉 device는 강한 일치 조건(불일치 시 거절), ip는 약한 조건(향후 경고 알림 대상)으로 구분한다.

## 영향

- 클라이언트는 발급 때와 동일한 `device` 식별자를 재발급 요청에 보내야 한다.
- 토큰 회전으로 인해 동일 refresh token 재사용은 1회로 제한된다.

## 향후 과제

- IP 불일치 시 사용자 경고/알림 기능 추가(TODO).

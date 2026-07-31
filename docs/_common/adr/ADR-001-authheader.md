# ADR-001. access token을 검증해 loginId 헤더로 전파한다

- 상태: 적용
- 범위: _common (전 도메인 공통)

## 맥락

여러 도메인 컨트롤러가 "현재 로그인 사용자"를 알아야 한다. 각 컨트롤러가 매번 `Authorization` 헤더를 파싱하고 토큰을 검증하면 중복이 크다. 인증 해석을 한 곳으로 모으고, 하위 계층에는 단순한 식별자만 넘기고 싶다.

## 결정

서블릿 필터에서 access token을 검증해 `loginId` 요청 헤더를 주입한다.

- `AccessTokenLoginIdHeaderFilter`(`OncePerRequestFilter`):
  1. 이미 `loginId` 헤더가 있으면 그대로 통과(외부 주입 방지를 위해 이후 단계는 신뢰 경계 안에서만 의미).
  2. `Authorization: Bearer <token>`에서 access token을 추출한다.
  3. `TokenStore`로 토큰을 조회해 `TokenMetadata`(memberId, role)를 얻는다.
  4. role로 분기해 `BuyerRepository`/`SellerRepository`에서 이메일을 조회하고, 이를 `loginId`로 본다.
  5. `MutableHttpServletRequest`로 요청을 감싸 `loginId` 헤더를 추가해 체인을 진행한다.
- 토큰이 없거나/무효이거나/회원을 못 찾으면 헤더를 주입하지 않고 그대로 통과한다(필터는 인가를 강제하지 않는다 — 인가 판단은 각 핸들러/리졸버 책임).
- 다운스트림(blog의 `AuthMemberResolver` 등)은 `loginId` 헤더만 읽어 사용자 맥락을 구성한다.

## 근거

- 토큰 파싱/검증 로직을 한 곳에 두어 컨트롤러를 단순화한다.
- 하위 계층은 토큰 형식(불투명/JWT)을 몰라도 되어, 토큰 방식 변경([`../../auth/adr/ADR-002-token.md`](../../auth/adr/ADR-002-token.md))의 영향이 격리된다.

## 트레이드오프 / 보안 주의

- `loginId` 헤더는 **내부 신뢰 경계 안에서만** 의미를 가진다. 외부에서 들어온 `loginId` 헤더를 그대로 신뢰하면 위장 위험이 있으므로, 엣지(게이트웨이/프록시)에서 클라이언트가 보낸 `loginId`를 제거하도록 운영에서 보장해야 한다.
- 필터가 매 요청 토큰을 조회하므로 Redis 조회 비용이 요청마다 든다([`../../_global/adr/ADR-002-redis.md`](../../_global/adr/ADR-002-redis.md)).
- 필터가 인가를 강제하지 않으므로, 보호가 필요한 엔드포인트는 핸들러 단에서 `loginId` 존재/역할을 검증해야 한다.

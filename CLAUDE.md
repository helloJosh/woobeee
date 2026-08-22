# CLAUDE.md

이 문서는 Claude Code가 `woobeee` 레포지토리에서 작업할 때 따르는 운영 기준이다.

## 프로젝트 개요

- **구조**: Maven 멀티모듈 (`core` / `app-mvc` / `app-webflux` / `front`), Java 25, Spring Boot 4.0.5
- **두 표면**: `blog`+`auth` = Spring MVC + JPA (`app-mvc` :8000) / `game` = Spring WebFlux + R2DBC (`app-webflux` :8001)
- **프론트엔드**: `front/` Next.js 14 + React 18 + TypeScript + Tailwind (shadcn/ui, Radix) + vitest. rewrites로 두 백엔드를 단일 오리진화 — **단 WebSocket(`/ws/game`)은 예외로 브라우저가 WebFlux 오리진에 직접 붙는다**
- **데이터/인프라**: PostgreSQL :9432(공유), Redis :9379(공유 토큰), MinIO :9000, Kafka :8010/8011 + UI :8090(코드 미연동), Google OAuth
- **전신**: `art-market-place` 단일 모듈 모놀리스. product/cart 도메인은 폐기했다.

### 모듈 경계

| 모듈 | 넣어도 되는 것 | 넣으면 안 되는 것 |
| --- | --- | --- |
| `core` | 두 앱이 공유하는 계약: `ApiResponse`, 토큰 계약, `RedisTokenStore` | `spring-boot-starter-webmvc` / `-webflux` 의존, 도메인 로직 |
| `app-mvc` | auth, blog, 블로킹 I/O, JPA 엔티티, Flyway 마이그레이션 | 게임 로직 |
| `app-webflux` | game, 논블로킹 I/O, R2DBC, `S3AsyncClient`(비동기 스토리지) | 블로킹 호출(JPA/JDBC/`StringRedisTemplate`), Flyway 실행, `S3Client`(블로킹) |

`core`가 웹 스타터를 끌어오면 두 앱 중 하나가 깨진다. 다음으로 검증한다:

```bash
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

## 문서 읽기 순서

1. `docs/superpowers/specs/2026-07-31-multimodule-game-blog-restructure-design.md` — 현 구조의 설계 근거
2. `docs/ARCHITECTURE.md` — 모듈 구조와 도메인별 흐름
3. `docs/_global/PRD.md`, `docs/_global/adr/` — 전체 목표, 인프라 ADR
4. 작업 도메인의 `docs/<domain>/PRD.md` 와 `docs/<domain>/adr/` (`auth`, `blog`, `game`, `front`)
5. `docs/FRONTEND.md`, `docs/DESIGN.md`
6. 관련 코드와 테스트

## 핵심 규칙

- **도메인 패키지 경계를 유지**한다: `com.woobeee.mvc.{_common,auth,blog}`, `com.woobeee.game`, `com.woobeee.core`. 도메인 간 직접 의존을 늘리지 않는다.
- **`core`는 웹 *스타터* 무의존**을 유지한다. `spring-web` 자체는 들어 있다 — `ApiResponse` 가
  `HttpStatus` 를 쓴다(`core/pom.xml`). 금지 대상은 서블릿·리액티브 런타임을 끌고 오는
  `spring-boot-starter-webmvc` / `-webflux` 이고, 아래 grep 게이트가 그것을 본다. 공통 코드가
  MVC나 WebFlux 타입을 필요로 하면 core가 아니라 해당 앱에 둔다.
- **쿼리 구현 규칙**:
  - 단순 조회(PK/단일 컬럼)는 Spring Data 파생 메서드.
  - 그 외 커스텀 조회(동적 조건·검색·집계·조인/서브쿼리·목록)는 **네이티브 SQL**(`@Query(nativeQuery = true)`).
  - 네이티브 쿼리는 **N+1을 해결한 형태**로 쓴다: 조인으로 한 번에, 또는 식별자를 모아 배치(IN) 조회. 루프 안 단건 조회 금지. 값은 바인딩 파라미터로만.
  - QueryDSL은 신규 사용 금지. `app-mvc`의 `blog/repository/PostQueryRepositoryImpl` 이 유일한 잔존 사용처이며 네이티브 SQL 전환 대상이다.
- **스키마는 Flyway가 단일 소스**다. `app-mvc/src/main/resources/db/migration/` 에 `V<n>__<name>.sql` 을 추가한다. JPA는 `validate` 전용이므로 엔티티만 바꾸면 부팅이 실패한다 — 마이그레이션을 함께 쓰고 `SchemaValidationTest` 를 통과시켜야 한다.
- **app-webflux에서 블로킹 호출 금지**. Redis는 `ReactiveStringRedisTemplate`, DB는 R2DBC. core의 `RedisTokenStore`(블로킹)는 app-mvc 전용이다.
- **토큰 계약을 바꿀 때는 양쪽을 함께 본다.** `core`의 `AuthTokenType` 키 규칙과 `TokenMetadata` 필드가 app-mvc(발급)와 app-webflux(검증)의 유일한 접점이다. `AuthTokenTypeTest` 가 이를 고정한다.
- **테스트는 PRD의 인수 기준에서 도출한다**:
  - 각 도메인 PRD의 `## 인수 기준 (Acceptance Criteria)` 표가 단일 출처다. 별도 테스트 케이스 문서는 두지 않는다.
  - 동작/API 계약을 바꾸면 **먼저 AC 표를 갱신**하고 그 AC를 커버하는 테스트를 추가/수정한다.
  - 테스트 메서드 이름·주석에 AC ID(예: `BLOG-AC-03`)를 참조해 PRD ↔ 테스트 추적을 유지한다.
  - AC 표가 없는 도메인은 테스트 백로그다. 현재 `blog` 와 **`front`** 가 그렇다 — `game` 은
    28행 표를 갖췄고 GAME-AC-25 한 줄만 테스트에서 ID 를 참조하지 않는다(테스트 자체는 있다).
    `front` 는 이 레포에서 가장 큰 스위트인데 AC 가 없다.
- **`DodgeGame` 을 고치면 프론트 포트의 골든을 다시 뽑는다.** `front/lib/dodge-engine.ts` 는 서버
  틱 로직의 TypeScript 포트이고 기보 재생이 여기에 걸려 있다. 한 글자만 달라져도 재생이 다른
  게임이 되는데, 골든을 갱신하지 않으면 프론트 테스트는 낡은 기대값에 대고 계속 초록이다.

  ```bash
  ./mvnw -pl core,app-webflux -am compile -DskipTests
  jshell --class-path app-webflux/target/classes -q scripts/dodge-parity-trace.jsh
  ```

  출력을 `front/lib/dodge-engine.test.ts` 의 `GOLDEN` 배열에 반영한다.
- **게임 오류 코드는 양방향으로 일치해야 한다.** `app-webflux` 의 `GameErrorCode` 와
  `front/lib/errors/error-messages.ts`(ko/en 둘 다) — 지도에 없는 코드도, 코드 없는 지도 키도
  `GameErrorCodeTest` 가 실패시킨다.
- 동작이나 구조가 바뀌면 해당 `docs/<domain>/*` 문서를 함께 갱신한다.

## 빌드 · 실행 · 검증

### 로컬 인프라

```bash
docker compose -f .docker-compose/docker-compose.yml up -d   # postgres 9432 · redis 9379 · minio 9000 · kafka 8010/8011 · kafka-ui 8090
```

Kafka는 인프라로만 떠 있고 **어떤 앱도 연동하지 않는다**(ADR-003). 게임 도메인에서 쓰려면 먼저
ADR을 갱신한다. 백엔드 개발에 Kafka가 필요 없으면 `up -d postgres-management redis minio` 로
필요한 것만 띄워도 된다.

### 기본 검증 명령

```bash
./mvnw -pl core,app-mvc,app-webflux -am test   # SchemaValidationTest는 PostgreSQL이 떠 있어야 통과
cd front && npm test && npm run build          # npm test = tsc --noEmit → vitest run
```

`front/package.json` 의 `test` 는 **타입 검사를 먼저** 돌리고 통과해야 vitest 로 넘어간다.
`next.config.mjs` 가 `typescript.ignoreBuildErrors` 를 켜 두었으므로 `npm run build` 만으로는
타입 검사가 되지 않는다 — `npm test`(또는 `npx tsc --noEmit`)를 반드시 함께 돌린다.

프론트의 판단 로직은 컴포넌트가 아니라 `front/lib/` 의 React-free 모듈에 두고 `lib/*.test.ts`
로 고정한다. 세 차례 뮤테이션 스윕에서 심은 84개(11 + 31 + 42) 중 `lib/` 안의 변이는 결국
전부 죽었고, **끝까지 죽지 않은 변이는 전부 React 컴포넌트 층에 있었다** — 컴포넌트 스펙이
하나도 없으니 당연하고, 그래서 판단이 컴포넌트로 넘어가는 만큼 검증 밖으로 나간다. 근거와
모듈 목록은 `docs/FRONTEND.md`.

`core`의 웹 스택 무의존 확인:

```bash
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

### 개발 서버

```bash
./mvnw -pl app-mvc spring-boot:run       # :8000  auth + blog
./mvnw -pl app-webflux spring-boot:run   # :8001  game
cd front && npm run dev                  # :3000  rewrites로 위 둘을 프록시
```

`-pl <module>` 단독 실행은 `com.woobeee:core` 가 로컬 리포에 설치돼 있어야 한다. 클린 클론에서는
먼저 `./mvnw -pl core -am install -DskipTests` 를 한 번 실행한다.

### 필요한 환경변수

미설정 시 `application.yaml` 기본값을 쓴다.

- app-mvc: `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- app-webflux: `R2DBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` (기보 업로드와 presign 에 쓴다 — 미설정이면 조용히 기본 버킷으로 간다)
- front: `MVC_ORIGIN`, `WEBFLUX_ORIGIN`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_WS_BASE_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (`front/.env.local.example` 참조)

## API 엔드포인트

전체 엔드포인트와 접근 권한(공개/로그인/ADMIN)은 **`docs/api/README.md`** 가 단일 정리본이다.
엔드포인트나 권한이 바뀌면 그 문서를 함께 갱신한다.

| 앱 | 도메인 | 베이스 경로 | 비고 |
| --- | --- | --- | --- |
| app-mvc | auth | `/api/auth` | `signup`, `login`, `callback-google`, `access-tokens`, `refresh-tokens`, `me`, `me/profile-image*` |
| app-mvc | blog | `/api/back/posts`, `/api/back/comments`, `/api/back/likes`, `/api/back/categories` | 게시글/댓글/좋아요/카테고리 — 게시글·카테고리 쓰기는 `ROLE_ADMIN` 전용 |
| app-webflux | game | `/api/game`, `/ws/game` | `health`, `me`, `rooms*`, `me/results`, `results/{id}/replay`, WebSocket 실시간 |

## 안전 수칙

- 위험 명령(`rm -rf`, `git reset --hard`, `git push --force`, destructive SQL)은 사용자의 명시적 승인 없이 실행하지 않는다.
- 구현/검증 중 테스트·빌드가 실패하면 원인을 요약하고 **수정 지속 / 부분 롤백 / 전체 롤백** 중 어디로 갈지 사용자에게 확인한다. 승인 없이 실패한 변경을 임의로 되돌리지 않는다.
- 전신 리포 `/Users/administrator/Documents/projects/art-market-place` 는 **수정하지 않는다**.

## 알려진 후속 과제

| 항목 | 내용 |
| --- | --- |
| front 잔존 페이지·API | `app/products`, `app/cart`, `app/chat` 은 폐기된 백엔드를 호출한다. 페이지뿐 아니라 `lib/api.ts` 의 `/api/products*`·`/api/buyers/{id}/carts/*` 클라이언트 여덟 개도 살아 있고 `next.config.mjs` 는 그 경로를 rewrite 하지 않는다. `app/products/[productId]/page.tsx` 에는 `/cart` 링크도 남아 있다 |
| front AC 미작성 | `docs/front/PRD.md` 에 `## 인수 기준` 표가 없다. 274개→331개로 이 레포에서 가장 큰 테스트 스위트인데 AC ID 를 참조하는 테스트가 하나도 없어, 위 "테스트는 PRD의 인수 기준에서 도출한다" 규칙이 가장 필요한 도메인에서 지켜지지 않는다 |
| 프론트 컴포넌트 무검증 | vitest 가 node 환경이고 jsdom 이 없다. 판단은 전부 `lib/` 로 옮겨 뒀지만 fetch 이펙트·재생 타이머·이펙트 정리(cleanup)가 **돌긴 하는지**는 아무도 확인하지 않는다 — `return () => …` 을 통째로 지워도 331개가 그대로 통과한다 |
| 기보 골든이 단방향 | `front/lib/dodge-engine.ts` 의 `GOLDEN` 은 `scripts/dodge-parity-trace.jsh` 로 손으로 다시 뽑는다. Java 쪽 `DodgeGame` 을 고치고 재컴파일·재실행하지 않으면 프론트 테스트는 낡은 기대값에 대고 계속 초록이다. 왕복 픽스처(`app-webflux/src/test/resources`)가 절반은 막지만, 골든 자체를 강제하는 CI 는 없다 |
| CI 없음 | 레포에 워크플로가 없다. `GameErrorCodeTest` 의 enum↔TS 지도 대조는 상대 경로에 `Assumptions.assumeTrue(Files.exists(...))` 를 걸어 두어, 파일이 옮겨지면 **말없이 건너뛴다** |
| app-mvc 에 catch-all advice 없음 | `AuthRestControllerAdvice` 가 `basePackages="com.woobeee.mvc.auth"` 로 좁혀져 있어 네 부류가 `ApiResponse` 봉투 밖으로 나간다: auth 서비스의 `ResponseStatusException` 12곳, `CustomInternalServerException`, blog 컨트롤러 전체의 빈 검증, `DELETE /api/auth/me/profile-image` 의 204. 프론트는 전부 "예기치 못한 오류" 한 문장으로 받는다. 게다가 app-mvc 는 `header.message` 에 영어 문장을 넣는데 프론트는 그 자리를 **코드 키**로 읽는다 — 반드시 어긋난다. app-webflux 는 `GameExceptionHandler` 로 이미 해결돼 있으니 그 모양을 옮기면 된다 |
| `ERROR` 프레임 일부가 여전히 방 전체로 | 명령 실패는 원인 세션으로 가도록 고쳤지만 두 경로는 의도적으로 방 전체다 — leave 정산 실패, 그리고 방이 `IN_PROGRESS` 로 넘어간 뒤 실패한 START. 둘 다 남은 참가자에게도 소식이라는 판단이다 |
| 나감 처리가 인앱 이동에서만 즉시 | `LEAVE` 는 라우트 이동에서만 보낸다. 탭을 닫거나 새로고침하면 30초 유예를 타므로, 8인 피하기에서는 그동안 움직이지 않는 유령이 판에 남아 충돌 판정을 받는다. `pagehide` 를 쓰면 새로고침까지 즉시 나감이 되어 유예의 목적이 사라진다 — 없애려면 서버 쪽 liveness 신호가 필요하다 |
| 소켓 계약 타입이 장식 | `ClientMessageType` / `ClientMessagePayloads` / `DodgeDirection` 을 아무도 import 하지 않는다. `send` 가 `(type: string, ...)` 이라 오타 난 타입 문자열도 컴파일되고 서버에서 조용히 버려진다 |
| MinIO CORS 미검증 — **기보 뷰어만 남았다** | 기보 뷰어는 presigned URL 을 rewrites 가 아니라 `S3_ENDPOINT` 로 직접 가져온다(`credentials:"omit"`). 막히면 원인 없는 일반 네트워크 문구만 뜬다. 같은 값이 서버 클라이언트와 브라우저용 presign 에 함께 쓰이므로, 클러스터 내부 주소를 넣으면 브라우저가 못 여는 URL 이 나온다. **글 이미지와 프로필 이미지는 앱 스트리밍으로 옮겨 이 문제에서 빠졌다**(`GET /api/back/posts/{id}/images/{파일명}`, `GET /api/auth/me/profile-image`). 기보도 같은 모양으로 옮기는 것이 남은 수다 |
| 고아 오브젝트 정리 | `profiles/` 의 고아 발생 경로는 사라졌다 — presigned 발급이 없어졌고 업로드가 앱을 거치며 즉시 컬럼에 등록되기 때문이다. 남은 경로는 **교체 시 이전 오브젝트 삭제 실패**뿐이다(의도적으로 조용히 넘긴다: 삭제가 프로필을 깨뜨리면 안 된다). 글 첨부(`{postId}/`)는 글이 지워져도 오브젝트가 남으므로 여전히 lifecycle 정책이 필요하다 |
| 게임 머니 증감 | `members.game_money` 는 항상 0이다. 증감 계약은 game spec에서 설계 |
| front 취약점 | 이관한 `package-lock.json` 기준 `npm audit` 17건(high 13, moderate 4). 전신 리포에서 그대로 넘어온 것 |
| QueryDSL 잔존 | `blog/repository/PostQueryRepositoryImpl` 을 네이티브 SQL로 전환 |
| blog AC 미작성 | `docs/blog/PRD.md` 의 인수 기준 표가 비어 있어 blog 테스트가 없다 |
| 이관 문서 잔여 언급 | `docs/ARCHITECTURE.md` 에 product/cart 절이 크게 남아 있고, 존재하지 않는 `docs/cart/` 로 가는 ADR 링크 두 개와 존재하지 않는 `.codex/settings.json` 참조, 그리고 클래스명과 다른 blog 엔티티 이름(`Post` vs `Posts`)이 있다. `docs/<domain>/adr/` 은 `auth`·`blog` 에만 있다 |
| 자잘한 미고정 | `auth-redirect.ts` 의 `startsWith("//")` 는 정규화 검사에 가려져 지워도 통과한다(중복일 뿐, 정규화 검사를 지워도 된다는 뜻이 아니다). `dodge-engine.ts` 가 Java 는 package-private 로 둔 이음매를 export 한다. `getBrowserLocale` 을 아무도 import 하지 않아 `en` 메시지 블록 전체가 런타임에 도달 불가다. `ErrorPayload.status` 는 채워지지만 읽는 곳이 없다. 경주 테스트 두 개가 각각 500ms 를 타임아웃으로 쓴다 |
| 3B·6B 잔여 (모두 사용자가 밟을 수 없음) | 스냅샷 브로드캐스트가 게임 모니터를 쥔 채 팬아웃한다. 이미 접속 중인 멤버가 새 세션으로 JOIN 하면 방 전체에 상태가 다시 나간다. 라우트 미스의 404 와 `GameAuthWebFilter` 안에서 난 오류는 봉투를 타지 않는다. `broadcastRoomState` 가 던지면 방금 입장한 참가자가 자기 ROOM_STATE 확인을 못 받는다 |
| 5B 잔여 (표시상) | `Header` 가 클라이언트 전용 `mounted` 로 렌더를 막아 서버 HTML 에는 없다 — 이제 전 라우트에서 하이드레이션 후 튀어나온다. `/blog` 로 클라이언트 이동할 때 한 프레임 동안 3탭만 그려진다. 죽은 `/products`·`/cart` 가 자체 sticky 헤더를 그려 전역 헤더 뒤로 숨는다. 인증 화면 넷이 `min-h-screen` 이라 64px 헤더 아래에서 살짝 어긋난다 |
| Kafka 미연동 | 로컬 compose에만 있고 코드 연동은 없다(ADR-003). 실제로 쓰려면 토픽 설계부터 |
| 하네스 재설계 | `art-market-place`의 `amp-backend-feature` 하네스는 art-marketplace 도메인 전제여서 이관하지 않았다. 필요 시 game/blog 기준으로 새로 구성 |
| 오목 제한시간 미강제 (G1) | `turnDeadline` 을 `OMOK_MOVED` 로 클라이언트에 알리지만 `OmokGame.timeout(...)` 을 호출하는 배선이 없다. 60초를 넘겨도 서버가 자동으로 패배 처리하지 않는다. 테스트: `OmokGameSinkTest#aStalledPlayerNeverTimesOutBecauseNothingDrivesTheDeadline` (`@Tag("known-gap")`) |
| `RoomSweeper` 가 진행 중 게임을 정리하지 않음 (G2) | TTL 만료 방은 `RoomCommandDispatcher.settle` 을 거치지 않고 바로 치워진다. 오목에서는 `OmokGameSink` 의 방별 상태가 메모리에 남는 정적 누수지만, **장애물피하기에서는 더 나쁘다** — `DodgeGameSink` 의 틱 타이머가 이미 사라진 방을 계속 돌리고, 끝내 존재하지 않는 방의 결과 행을 DB에 쓴다. 즉 조용한 메모리 누수가 아니라 능동적인 오염이다. 두 결과가 다르므로 테스트도 둘이다(둘 다 `@Tag("known-gap")`): 오목의 정적 누수는 `RoomSweeperTest#sweepingAnExpiredRoomLeavesTheOmokSinkStillHoldingItsGameState` 가, 장애물피하기의 능동적 오염은 `RoomSweeperTest#sweepingAnExpiredRoomLeavesTheDodgeTimerRunningUntilItRecordsAPhantomResult` 가 고정한다 — 후자는 방을 치운 뒤 시간을 더 흘려 보내면 이미 사라진 방에 대해 `GameResultService.record` 가 실제로 불리는 것(=`game_results` 행과 S3 기보가 남는 것)을 실패로 잡는다. 맵에 항목이 남았다는 것이 아니라 저장소가 오염된다는 것이 요점이다 |
| **기보 접근 권한 실DB 테스트** | GAME-AC-22(참가자 본인만 기보 URL)를 강제하는 SQL이 **텍스트로만** 고정돼 있다. `AND p.member_id = :memberId` 를 지우면 테스트가 깨지지만, 컬럼을 바꾸거나 `AND` 를 `OR` 로 바꾸면 통과한다. 셋 중 가장 위험 — **UI가 이 엔드포인트에 붙기 전에** 실 Postgres 대상 테스트로 막아야 한다(참가자·비참가자·없는 id·게스트 4케이스) |
| 결과 저장 롤백 미검증 | `GameResultService` 가 `insertResult`+`insertParticipants` 를 트랜잭션으로 감싸는 것은 테스트로 고정됐지만, 실제 commit/rollback 은 검증되지 않았다. 통합 테스트로 두 번째 참가자 insert 를 실패시켜 `game_results` 가 0행인지 확인해야 한다 |
| `game_result_id` FK 없음 (G5) | `game_result_participants.game_result_id` 에 `game_results` 로의 FK 가 없다. 지금은 삭제 경로가 없어 고아 행이 생길 수 없다. 붙이려면 V4(회원 role)까지 적용됐으므로 `V5__game_result_fk.sql` 로 `ON DELETE CASCADE` 를 추가한다. 테스트: `GameResultParticipantsForeignKeyTest#insertingAParticipantForAMissingGameResultIsRejected` (`@Tag("known-gap")`, 실 Postgres 필요) |
| 열린사 판정이 렌주보다 느슨함 (G4) | `FourRule.makesStraightFour` 가 양끝 중 **하나만** 정확히 5를 만들어도 참을 반환한다(`||`). 정통 렌주의 열린사는 승리점이 둘이다. 수정 전의 모양만 보는 판정보다는 좁으므로 회귀는 아니지만, 엄밀히 맞추려면 `&&` 로 조인다 — 단, 그렇게 하면 `RenjuRuleTest#twoDistinctThreeGroupsOnOneAxisAreDoubleThree` 가 깨진다(서로 다른 두 삼이 각자 반대편 그룹을 향해 장목이 되는 경우의 판정을 다시 설계해야 한다). 테스트: `FourRuleTest#bothFlanksMustCompleteToFiveNotJustOne` (`@Tag("known-gap")`) |
| 앱 컨텍스트 기동 테스트 없음 | `app-webflux` 에는 `@WebFluxTest` 슬라이스만 있고 전체 컨텍스트를 띄우는 테스트가 없다. 빈 그래프가 실제로 기동하는지 CI가 확인하지 못한다 |
| 사인의 테스트 전용 접근자 | `DodgeGameSink` 의 `gameOf`/`pendingInputOf`/`timerOf`/`holdsAnyStateFor` 는 테스트만 쓰는 package-private 접근자다. 방별 맵을 관측하려면 지금은 이 방법뿐이지만 넷까지 늘었다. 상태 관측을 하나의 스냅샷 레코드로 합치는 편이 낫다 |
| 기보 재생 상한 처리 불일치 | 공유 스텝 함수(`stepReplay`)는 같은데 상한에서의 태도가 다르다 — `rerunReplay` 는 `REPLAY_MAX_TICKS` 에서 던지고 마이페이지 뷰어는 조용히 자른다(Plan 4 문서 기준). 잘린 기보가 정상 재생처럼 보인다. 뷰어도 사용자에게 드러내야 한다 |
| 장애물 생성 상한 경계 미검증 | 스폰 확률 상한 테스트가 실제로 상한이 걸리는 틱 1400 이 아니라 100000 을 쓴다. 값 자체는 고정돼 있으나 경계는 산술로만 보장된다 |
| 시나리오 생성자 입력 미검증 | `DodgeGame` 의 package-private 시나리오 생성자가 `startingPositions` 가 `participantIds` 를 모두 덮는지 확인하지 않는다. 덮지 않으면 참가자가 `finalRanks` 에서 조용히 빠진다. 현재 호출자는 하나뿐이고 올바르게 만든다 |

### 알려진 결함을 실행 가능한 테스트로 고정한 것 (`known-gap`)

위 표의 G1/G2/G4/G5 네 항목은 리뷰에서 발견됐지만 의도적으로 미루기로 한 결함이다. (원래
G3 — 게임이 끝나도 방이 `FINISHED` 로 가지 않음 — 도 있었으나 재대국(GAME-AC-30) 구현과 함께
고쳐져 일반 테스트로 승격됐다.) 각각을 "오늘은 실패하고, 결함이 고쳐지면 통과하는" 테스트로
고정해 뒀다 — 메모가 아니라 실행 가능한 할 일이다. **테스트는 다섯 개다**: G2 만 결과가 게임
종류에 따라 다르므로 둘로 나뉜다(오목은 정적 누수, 장애물피하기는 이미 사라진 방의 결과 행을
쓰는 능동적 오염). 다섯 테스트 모두 `@Tag("known-gap")` 을 달고 있고, 기본 `./mvnw test`
에서는 제외된다(그래서 위 기본 검증 명령은 계속 358개 그린을 유지한다 — core 6 / app-mvc 51 /
app-webflux 301. 포함해서 돌리면 363개 중 358개 통과, 5개 실패다). 루트 `pom.xml` 의
`known.gap.excludedGroups` 프로퍼티(기본값 `known-gap`)가 surefire의 `excludedGroups` 를
구동한다.

```bash
./mvnw -pl core,app-mvc,app-webflux -am test                              # 기본: known-gap 제외, 그린 유지
./mvnw -pl core,app-mvc,app-webflux -am test -Dknown.gap.excludedGroups=   # known-gap 포함: 다섯 개가 실패해야 정상
```

G5 테스트(`GameResultParticipantsForeignKeyTest`)는 `docker compose` 의 Postgres(`localhost:9432/market`,
`root`/`123456789`)에 직접 R2DBC 로 접속한다 — 인프라를 띄워야 통과/실패를 관찰할 수 있다.

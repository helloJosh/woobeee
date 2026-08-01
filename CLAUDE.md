# CLAUDE.md

이 문서는 Claude Code가 `woobeee` 레포지토리에서 작업할 때 따르는 운영 기준이다.

## 프로젝트 개요

- **구조**: Maven 멀티모듈 (`core` / `app-mvc` / `app-webflux` / `front`), Java 25, Spring Boot 4.0.5
- **두 표면**: `blog`+`auth` = Spring MVC + JPA (`app-mvc` :8000) / `game` = Spring WebFlux + R2DBC (`app-webflux` :8001)
- **프론트엔드**: `front/` Next.js 14 + React 18 + TypeScript + Tailwind (shadcn/ui, Radix). rewrites로 두 백엔드를 단일 오리진화
- **데이터/인프라**: PostgreSQL :9432(공유), Redis :9379(공유 토큰), MinIO :9000, Kafka :8010/8011 + UI :8090(코드 미연동), Google OAuth
- **전신**: `art-market-place` 단일 모듈 모놀리스. product/cart 도메인은 폐기했다.

### 모듈 경계

| 모듈 | 넣어도 되는 것 | 넣으면 안 되는 것 |
| --- | --- | --- |
| `core` | 두 앱이 공유하는 계약: `ApiResponse`, 토큰 계약, `RedisTokenStore` | `spring-boot-starter-webmvc` / `-webflux` 의존, 도메인 로직 |
| `app-mvc` | auth, blog, 블로킹 I/O, JPA 엔티티, Flyway 마이그레이션 | 게임 로직 |
| `app-webflux` | game, 논블로킹 I/O, R2DBC | 블로킹 호출(JPA/JDBC/`StringRedisTemplate`), Flyway 실행 |

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
- **`core`는 웹 스택 무의존**을 유지한다. 공통 코드가 MVC나 WebFlux 타입을 필요로 하면 core가 아니라 해당 앱에 둔다.
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
  - AC가 "미작성"인 도메인(`blog`, `game`)은 테스트 백로그다.
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
cd front && npm run build
```

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
- app-webflux: `R2DBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- front: `MVC_ORIGIN`, `WEBFLUX_ORIGIN`, `NEXT_PUBLIC_API_BASE_URL` (`front/.env.local.example` 참조)

## API 엔드포인트

| 앱 | 도메인 | 베이스 경로 | 비고 |
| --- | --- | --- | --- |
| app-mvc | auth | `/api/auth` | `signup`, `login`, `callback-google`, `access-tokens`, `refresh-tokens`, `me`, `me/profile-image*` |
| app-mvc | blog | `/api/back/posts`, `/api/back/comments`, `/api/back/likes`, `/api/back/categories` | 게시글/댓글/좋아요/카테고리 |
| app-webflux | game | `/api/game` | `health`(공개), `me`(인증) — 나머지는 후속 spec |

## 안전 수칙

- 위험 명령(`rm -rf`, `git reset --hard`, `git push --force`, destructive SQL)은 사용자의 명시적 승인 없이 실행하지 않는다.
- 구현/검증 중 테스트·빌드가 실패하면 원인을 요약하고 **수정 지속 / 부분 롤백 / 전체 롤백** 중 어디로 갈지 사용자에게 확인한다. 승인 없이 실패한 변경을 임의로 되돌리지 않는다.
- 전신 리포 `/Users/administrator/Documents/projects/art-market-place` 는 **수정하지 않는다**.

## 알려진 후속 과제

| 항목 | 내용 |
| --- | --- |
| 게임 도메인 설계 | 실시간 통신 방식(WebSocket/SSE/폴링), 게임 규칙, `V2__game.sql` → 별도 spec |
| front 잔존 페이지 | `app/products`, `app/cart`, `app/chat` 은 폐기된 백엔드를 호출한다. 삭제 또는 게임 화면으로 대체 |
| 프로필 이미지 front UI | 백엔드 계약만 있고 업로드·표시 화면이 없다 |
| 고아 오브젝트 정리 | 발급받고 등록하지 않은 `profiles/` 업로드에 대한 lifecycle 정책 |
| 게임 머니 증감 | `members.game_money` 는 항상 0이다. 증감 계약은 game spec에서 설계 |
| front 취약점 | 이관한 `package-lock.json` 기준 `npm audit` 17건(high 13, moderate 4). 전신 리포에서 그대로 넘어온 것 |
| QueryDSL 잔존 | `blog/repository/PostQueryRepositoryImpl` 을 네이티브 SQL로 전환 |
| blog AC 미작성 | `docs/blog/PRD.md` 의 인수 기준 표가 비어 있어 blog 테스트가 없다 |
| 이관 문서 잔여 언급 | `docs/` 의 이관 문서 일부에 product/cart 언급이 남아 있다. 도메인 문서를 손댈 때 함께 정리 |
| Kafka 미연동 | 로컬 compose에만 있고 코드 연동은 없다(ADR-003). 실제로 쓰려면 토픽 설계부터 |
| 하네스 재설계 | `art-market-place`의 `amp-backend-feature` 하네스는 art-marketplace 도메인 전제여서 이관하지 않았다. 필요 시 game/blog 기준으로 새로 구성 |

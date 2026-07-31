# 멀티모듈 재구성 설계: game(WebFlux) + blog(MVC) 한 앱

- 날짜: 2026-07-31
- 상태: 설계 확정 대기 (사용자 검토 예정)
- 대상: 신규 프로젝트 `woobeee` (기존 `art-market-place`에서 auth/blog 재사용, product/cart 폐기)

## 1. 배경 / 목표

기존 `art-market-place`(Spring Boot 단일 모듈 모놀리스, 아트 마켓플레이스 도메인)를 접고,
**게임과 블로그를 함께 서비스하는 하나의 앱**으로 전환한다. 두 표면은 웹 스택이 다르다:

- **게임** — 실시간/고동시성 성격 → **Spring WebFlux + R2DBC** (논블로킹)
- **블로그** — 전통적 CRUD → **Spring MVC + JPA** (기존 blog/auth 재사용)

회원·인증·DB·Redis는 **공유**하여 "하나의 앱"으로 동작하되, WebFlux 서버(Netty)와 MVC
서버(Tomcat)는 한 JVM 프로세스에 함께 뜰 수 없으므로 **두 개의 Spring Boot 프로세스**로
분리한다. 프론트(Next.js)가 rewrites로 두 백엔드를 하나의 오리진처럼 묶는다.

### 확정된 결정 (브레인스토밍 Q&A 결과)

| 항목 | 결정 |
| --- | --- |
| WebFlux/MVC 역할 | 게임=WebFlux, 블로그=MVC (한 앱에 함께 서비스) |
| 공유 범위 | 회원·인증·DB·도메인 공유 (하나의 앱) |
| 프로세스 토폴로지 | 진짜 리액티브: 게임 WebFlux+R2DBC / 블로그 MVC+JPA (프로세스 2개) |
| 빌드 도구 | Maven 멀티모듈 유지 (parent POM + 서브모듈) |
| 기존 코드 | auth + blog 재사용, product/cart 폐기 |
| 프론트↔백엔드 | Next.js rewrites 직결 (`/api/blog/*`→MVC, `/api/game/*`→WebFlux) |
| 스키마 관리 | Flyway 단일 소스 (R2DBC는 auto-DDL 없음) |
| 인증 공유 | 공유 Redis 토큰 스토어 — MVC가 발급, 양쪽이 검증 |

## 2. 아키텍처 개요

```text
                    Next.js (front)
              /api/blog/*      /api/game/*
                    │               │
               [app-mvc]       [app-webflux]
               Tomcat/JPA      Netty/R2DBC
                    │               │
                    └───── core ────┘
              (ApiResponse · 공통예외 · TokenMetadata · Redis 토큰스토어)
                    │               │
            ┌───────┴───────────────┴───────┐
       PostgreSQL (공유)            Redis (공유 토큰)
            └──── Flyway가 스키마 단일 관리 ────┘
```

- 논리적으로 하나의 앱(회원·DB·Redis 공유), 물리적으로 프로세스 2개.
- 프론트가 rewrites로 두 백엔드를 단일 오리진처럼 노출 → CORS/게이트웨이 불필요.

## 3. 모듈 구성 (Maven parent POM)

parent POM `<packaging>pom</packaging>` 아래 서브모듈:

| 모듈 | 스택 | 책임 | 의존 |
| --- | --- | --- | --- |
| `core` | 순수 Java 라이브러리 (web 스택 **무의존**) | `ApiResponse`, 공통 예외, `TokenMetadata` 계약, Redis 토큰 스토어(양쪽 검증 공유), 공통 config | — |
| `app-mvc` | Boot + `starter-web` + JPA | **auth**(토큰 발급·로그인·OAuth) + **blog** 이관, `/api/...` | core |
| `app-webflux` | Boot + `starter-webflux` + R2DBC | **game**(신규), Redis로 토큰 **검증만** | core |
| `front` | Next.js | rewrites로 두 백엔드 프록시 | — |

**핵심 제약:** `core`는 `spring-boot-starter-web`/`spring-boot-starter-webflux` 어느 것도
의존하지 않아야 한다(그래야 두 앱에 안전하게 물린다). Redis 클라이언트는 웹 스택과 무관하므로
core에 두어도 무방하다.

디렉토리(안):

```text
woobeee/
├── pom.xml                 (parent, packaging=pom)
├── core/
│   └── src/main/java/com/woobeee/core/...
├── app-mvc/
│   └── src/main/java/com/woobeee/mvc/{auth,blog}/...
├── app-webflux/
│   └── src/main/java/com/woobeee/game/...
├── front/                  (Next.js)
├── db/migration/           (Flyway: V1__auth_blog.sql, V2__game.sql)
├── docs/
└── .docker-compose/
```

## 4. 인증 데이터 흐름

1. 로그인/OAuth → **app-mvc(auth)** 가 access/refresh 토큰 발급, Redis에 `TokenMetadata` 저장.
2. 블로그 요청 → app-mvc가 기존 필터로 검증(loginId 헤더 주입) — 기존 동작 유지.
3. 게임 요청 → **app-webflux** 가 `WebFilter`에서 **Redis 토큰을 직접 읽어 검증**(reactive
   Redis). 앱 간 HTTP 호출 없음(결합도·지연 최소). 회원 정보 필요 시 R2DBC로 회원 테이블 조회.
4. 토큰 계약(`TokenMetadata`, Redis 키 규칙)은 `core`에 두어 양쪽이 동일 포맷 공유.

## 5. 스키마 / 데이터 (Flyway)

- 한 PostgreSQL, **Flyway가 스키마 단일 소스**.
  - `V1__auth_blog.sql` — 기존 auth/blog 테이블(기존 `docs/_ddl.sql`에서 이관).
  - `V2__game.sql` — 게임 테이블(신규).
- app-mvc의 JPA는 `ddl-auto=validate` — 매핑↔스키마 검증만(기존 규칙·`SchemaValidationTest`
  유지).
- app-webflux의 R2DBC는 Flyway가 생성한 테이블 위에서 동작(R2DBC는 auto-DDL 없음).
- Flyway는 JDBC로 실행 → webflux 앱에도 부트 타임에 JDBC 드라이버 필요(런타임은 R2DBC).
  마이그레이션 실행 주체는 **한 앱만** 담당(중복 실행 방지). 기본은 app-mvc가 소유, webflux는
  `flyway.enabled=false`로 두고 검증만.

## 6. 배포 (docker-compose)

서비스: `postgres`, `redis` + `app-mvc`(:8000), `app-webflux`(:8001), `front`(:3000).
프론트 rewrites가 두 백엔드로 프록시. 로컬은 기존 compose에 두 앱 서비스 추가.

## 7. 검증 전략

- **app-mvc**: 기존 `SchemaValidationTest` + blog/auth AC 테스트 유지.
- **app-webflux**: R2DBC 리포지토리 테스트(`@DataR2dbcTest`), 컨트롤러 `WebTestClient`.
- **front**: `npm run build` 유지.
- 멀티모듈 전체 빌드: `./mvnw -pl core,app-mvc,app-webflux -am test`.

## 8. 마이그레이션 단계

1. parent POM + `core` 골격 생성.
2. 기존 auth/blog를 `app-mvc`로 이동, product/cart 제거.
3. Flyway 도입(기존 `_ddl.sql` → `V1__auth_blog.sql`), JPA `validate`로 전환.
4. `app-webflux` 골격 + Redis 검증 `WebFilter` + R2DBC 설정.
5. front rewrites 설정.
6. docker-compose 갱신(두 앱 서비스 추가).
7. 전체 검증(빌드/테스트/프론트 빌드).

> 게임 도메인의 기능 설계(실시간 멀티플레이/WebSocket/SSE 여부, 게임 규칙 등)는 이 재구성
> 이후 **별도 spec**으로 다룬다. 본 문서는 모듈/아키텍처 재구성에 한정한다.

## 9. 리스크 / 후속 과제

- **문서 전면 개정**: 현 `CLAUDE.md`는 단일모듈·아트마켓 도메인 전제 → 멀티모듈·game/blog로
  전면 개정 필요.
- **하네스 무효화**: `amp-backend-feature` 등 기존 하네스는 art-marketplace 도메인 전제 →
  재구성 후 재설계 필요.
- **Flyway ↔ R2DBC 조합**: webflux 앱에 JDBC 드라이버(부트 타임) + R2DBC 드라이버(런타임)
  양쪽 필요. 마이그레이션 소유 앱을 하나로 고정.
- **회원 ID 타입 정합성**: 기존 `Address.member_id`가 UUID였던 것과 달리 buyers/sellers는
  BIGINT. 신규 스키마에서 회원 참조 타입을 일관되게 정리(BIGINT 권장).

## 10. 결정된 기본값 / 후속 결정

- 신규 프로젝트 루트: `/Users/administrator/Documents/projects/woobeee` (art-market-place의 형제 디렉토리) — **확정**.
- 패키지 베이스: `com.woobeee.core` / `com.woobeee.mvc.{auth,blog}` / `com.woobeee.game` — 기본값(구현 착수 시 조정 가능).
- 게임의 실시간 통신 방식(WebSocket vs SSE vs 폴링), 게임 도메인 규칙 → **후속 spec에서 결정**.

# woobeee

게임(WebFlux)과 블로그(MVC)를 함께 서비스하는 Maven 멀티모듈 애플리케이션.

## 모듈

| 모듈 | 스택 | 포트 | 책임 |
| --- | --- | --- | --- |
| `core` | 순수 Java 라이브러리 | — | 두 앱이 공유하는 `ApiResponse` · 토큰 계약 · Redis 토큰스토어 |
| `app-mvc` | Spring MVC + JPA (Tomcat) | 8000 | `auth`(토큰 발급·로그인·Google OAuth) + `blog` |
| `app-webflux` | Spring WebFlux + R2DBC (Netty) | 8001 | `game` (골격) |
| `front` | Next.js 14 | 3000 | rewrites로 두 백엔드를 단일 오리진화 |

논리적으로 하나의 앱(회원·PostgreSQL·Redis 공유), 물리적으로 Boot 프로세스 2개.

## 시작하기

```bash
# 1) 로컬 인프라 (PostgreSQL 9432 · Redis 9379 · MinIO 9000)
docker compose -f .docker-compose/docker-compose.yml up -d

# 2) core 를 로컬 리포에 설치 (모듈 단독 실행에 필요, 최초 1회)
./mvnw -pl core -am install -DskipTests

# 3) 백엔드 — 최초 기동 시 app-mvc의 Flyway가 스키마를 만든다
./mvnw -pl app-mvc spring-boot:run        # :8000
./mvnw -pl app-webflux spring-boot:run    # :8001

# 4) 프론트
cd front && npm install && npm run dev    # :3000
```

## 검증

```bash
./mvnw -pl core,app-mvc,app-webflux -am test   # SchemaValidationTest는 PostgreSQL 필요
cd front && npm run build
```

## 스키마

Flyway가 단일 소스다: `app-mvc/src/main/resources/db/migration/`. JPA는 `validate` 전용이므로
엔티티 변경 시 마이그레이션을 함께 추가해야 한다. `app-webflux` 는 `flyway.enabled=false` 로
기존 테이블 위에서만 동작한다.

## 문서

`docs/` — 설계 근거는 `docs/superpowers/specs/`, 아키텍처는 `docs/ARCHITECTURE.md`,
작업 규칙은 `CLAUDE.md`.

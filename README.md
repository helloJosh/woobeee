# woobeee

블로그·일정·인증·포켓코인 공동구매를 서비스하는 Maven 멀티모듈 애플리케이션 (Spring MVC + Next.js).

## 모듈

| 모듈 | 스택 | 포트 | 책임 |
| --- | --- | --- | --- |
| `core` | 순수 Java 라이브러리 | — | 공유 계약 `ApiResponse` · 토큰 계약 · Redis 토큰스토어 (현재 소비자는 `app-mvc` 하나) |
| `app-mvc` | Spring MVC + JPA (Tomcat) | 8000 | `auth`(토큰 발급·로그인·Google OAuth) + `blog` + `schedule` + `pokemon` |
| `front` | Next.js 14 | 3000 | rewrites로 `app-mvc` 를 단일 오리진화 |

백엔드는 `app-mvc` 단일 Boot 프로세스다. 컨테이너 이미지는 `app-mvc/Dockerfile`, `front/Dockerfile` 이고 compose 서비스 `app-mvc` · `front` 가 `prod-docker` 프로파일(`app-mvc/src/main/resources/application-prod-docker.yml`)로 뜬다.

## 시작하기

```bash
# 1) 로컬 인프라 (PostgreSQL 9432 · Redis 9379 · MinIO 9000)
docker compose -f .docker-compose/docker-compose.yml up -d

# 2) core 를 로컬 리포에 설치 (모듈 단독 실행에 필요, 최초 1회)
./mvnw -pl core -am install -DskipTests

# 3) 백엔드 — 최초 기동 시 app-mvc의 Flyway가 스키마를 만든다
./mvnw -pl app-mvc spring-boot:run        # :8000

# 4) 프론트
cd front && npm install && npm run dev    # :3000
```

## 검증

```bash
./mvnw -pl core,app-mvc -am test   # SchemaValidationTest는 PostgreSQL 필요
cd front && npm run build
```

## 스키마

Flyway가 단일 소스다: `app-mvc/src/main/resources/db/migration/`. JPA는 `validate` 전용이므로
엔티티 변경 시 마이그레이션을 함께 추가해야 한다. `V25__drop_game.sql` 이 게임 테이블
(`game_results`, `game_result_participants`)과 `members.game_money` 컬럼을 삭제했으며 되돌릴 수 없다.

> compose 의 `app-mvc` 서비스는 실제 `market` DB 에 붙고 부팅 때 Flyway 가 돈다. 띄우면 밀린
> 마이그레이션(위 V25 포함)이 그 DB 에 적용된다.

## 문서

`docs/` — 설계 근거는 `docs/superpowers/specs/`, 아키텍처는 `docs/ARCHITECTURE.md`,
작업 규칙은 `CLAUDE.md`.
## 서버 실행

`scripts/dev-up.sh` 가 app-mvc(:8000) · front(:3000) 둘을 백그라운드로
띄우고 실제로 떴는지 포트로 확인한다. 로그는 각각 `app-mvc.log` ·
`front/dev.log`. 내릴 때는 `scripts/dev-down.sh`.

```bash
export GOOGLE_CLIENT_SECRET='실제-시크릿'
export GOOGLE_REDIRECT_URI='https://www.woobeee.com/auth/google/callback'
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

./scripts/dev-down.sh && ./scripts/dev-up.sh
```

jar 를 다시 빌드할 때는 **반드시 먼저 내린다**. 실행 중인 jar 를 덮어쓰면 JVM 이 클래스를
지연 로딩하다 `ClassNotFoundException` 으로 죽는다.

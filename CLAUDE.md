# CLAUDE.md

이 문서는 Claude Code가 `woobeee` 레포지토리에서 작업할 때 따르는 운영 기준이다.

## 프로젝트 개요

- **구조**: Maven 멀티모듈 (`core` / `app-mvc` / `front`), Java 25, Spring Boot 4.0.5
- **백엔드**: `auth`·`blog`·`schedule`·`pokemon`·`ai` = Spring MVC + JPA (`app-mvc` :8000) 단일 앱. 전에 있던 `game`(WebFlux/R2DBC/WebSocket, `app-webflux`)은 제거했다(`V26__drop_game.sql`)
- **프론트엔드**: `front/` Next.js 14 + React 18 + TypeScript + Tailwind (shadcn/ui, Radix) + vitest. rewrites로 백엔드(app-mvc)를 단일 오리진화
- **데이터/인프라**: PostgreSQL :9432(공유), Redis :9379(공유 토큰), MinIO :9000, Kafka :8010/8011 + UI :8090(`ai` 요청 전달 전용, ADR-005), Google OAuth
- **전신**: `art-market-place` 단일 모듈 모놀리스. product/cart 도메인은 폐기했다.

### 모듈 경계

| 모듈 | 넣어도 되는 것 | 넣으면 안 되는 것 |
| --- | --- | --- |
| `core` | 앱이 공유하는 계약: `ApiResponse`, 토큰 계약, `RedisTokenStore` | `spring-boot-starter-webmvc` / `-webflux` 의존, 도메인 로직 |
| `app-mvc` | auth, blog, schedule, pokemon, ai, 블로킹 I/O, JPA 엔티티, Flyway 마이그레이션 | — |

`core`는 웹 스타터 무의존을 유지한다(과거 두 앱이 공유하던 계약이라 생긴 규칙이며, 앱이 하나인 지금도 유지한다). 다음으로 검증한다:

```bash
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
```

## 문서 읽기 순서

1. `docs/superpowers/specs/2026-07-31-multimodule-game-blog-restructure-design.md` — 현 구조의 설계 근거
2. `docs/ARCHITECTURE.md` — 모듈 구조와 도메인별 흐름
3. `docs/_global/PRD.md`, `docs/_global/adr/` — 전체 목표, 인프라 ADR
4. 작업 도메인의 `docs/<domain>/PRD.md` 와 `docs/<domain>/adr/` (`auth`, `blog`, `front`, `schedule`, `pokemon`, `ai`)
5. `docs/FRONTEND.md`, `docs/DESIGN.md`
6. 관련 코드와 테스트

## 핵심 규칙

- **도메인 패키지 경계를 유지**한다: `com.woobeee.mvc.{_common,auth,blog,schedule,pokemon,ai}`, `com.woobeee.core`. 도메인 간 직접 의존을 늘리지 않는다.
- **`core`는 웹 *스타터* 무의존**을 유지한다. `spring-web` 자체는 들어 있다 — `ApiResponse` 가
  `HttpStatus` 를 쓴다(`core/pom.xml`). 금지 대상은 서블릿·리액티브 런타임을 끌고 오는
  `spring-boot-starter-webmvc` / `-webflux` 이고, 아래 grep 게이트가 그것을 본다. 공통 코드가
  MVC 타입을 필요로 하면 core가 아니라 app-mvc 에 둔다.
- **쿼리 구현 규칙**:
  - 단순 조회(PK/단일 컬럼)는 Spring Data 파생 메서드.
  - 그 외 커스텀 조회(동적 조건·검색·집계·조인/서브쿼리·목록)는 **네이티브 SQL**(`@Query(nativeQuery = true)`).
  - 네이티브 쿼리는 **N+1을 해결한 형태**로 쓴다: 조인으로 한 번에, 또는 식별자를 모아 배치(IN) 조회. 루프 안 단건 조회 금지. 값은 바인딩 파라미터로만.
  - QueryDSL은 쓰지 않는다. 마지막 잔존처였던 `blog/repository/PostQueryRepositoryImpl` 도 2026-09-09 에 네이티브 SQL 로 전환했고 의존을 걷어냈다.
- **스키마는 Flyway가 단일 소스**다. `app-mvc/src/main/resources/db/migration/` 에 `V<n>__<name>.sql` 을 추가한다. JPA는 `validate` 전용이므로 엔티티만 바꾸면 부팅이 실패한다 — 마이그레이션을 함께 쓰고 `SchemaValidationTest` 를 통과시켜야 한다.
- **토큰 계약을 바꿀 때는 `core` 를 본다.** `core`의 `AuthTokenType` 키 규칙과 `TokenMetadata` 필드가 토큰 계약이다(발급·검증 모두 app-mvc). `AuthTokenTypeTest` 가 이를 고정한다.
- **테스트는 PRD의 인수 기준에서 도출한다**:
  - 각 도메인 PRD의 `## 인수 기준 (Acceptance Criteria)` 표가 단일 출처다. 별도 테스트 케이스 문서는 두지 않는다.
  - 동작/API 계약을 바꾸면 **먼저 AC 표를 갱신**하고 그 AC를 커버하는 테스트를 추가/수정한다.
  - 테스트 메서드 이름·주석에 AC ID(예: `BLOG-AC-03`)를 참조해 PRD ↔ 테스트 추적을 유지한다.
  - AC 표가 없는 도메인은 테스트 백로그다. 현재 `blog` 와 **`front`** 가 그렇다 — `front` 는 이 레포에서 가장 큰 스위트인데 AC 가 없다.
- 동작이나 구조가 바뀌면 해당 `docs/<domain>/*` 문서를 함께 갱신한다.

## 빌드 · 실행 · 검증

### 로컬 인프라

```bash
docker compose -f .docker-compose/docker-compose.yml up -d   # postgres 9432 · redis 9379 · minio 9000 · kafka 8010/8011 · kafka-ui 8090
```

Kafka는 `ai` 도메인의 요청 전달(`ai.request.v1`)에만 쓴다(ADR-005). 그 밖의 도메인에서 쓰려면 먼저
ADR을 쓴다. 백엔드 개발에 Kafka가 필요 없으면 `up -d postgres-management redis minio` 로
필요한 것만 띄워도 된다.

### 기본 검증 명령

DB 를 쓰는 테스트는 `@ActiveProfiles("test")` 로 **`market_test`** 만 본다. 공유 DB 를 건드리지
않으므로 마음껏 지우고 넣어도 된다(`ScheduleRepositoryTest` 는 빈 DB 를 전제로 쓰였고, 공유
DB 에서는 남의 데이터 때문에 실패했다).

```bash
./mvnw -pl core,app-mvc -am test   # (339개) PostgreSQL 이 떠 있어야 하고, market_test 가 있어야 한다. Redis 도 필요(통합 테스트가 앱 컨텍스트를 올린다)
cd front && npm test && npm run build          # (318개) npm test = tsc --noEmit → vitest run
cd ai-worker && npm test && npm run build      # (57개) npm test = tsc --noEmit → vitest run. cmux 같은 환경에서는 `env -u NODE_OPTIONS` 를 앞에 붙인다
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

### 프로파일 — 배포 / dev / test

`application.yaml` 은 **배포 기준**이다. 로컬에서 띄울 때는 프로파일을 지정한다.

| 프로파일 | DB | 포트 | 쓰임 |
| --- | --- | --- | --- |
| (없음) | `market` | 8000 | 배포. 환경변수로 실제 주소를 주입한다 |
| `dev` | `market` (**공유, 실데이터**) | 8000 | 로컬 개발. SQL·바인딩 파라미터 로그를 켠다 |
| `test` | `market_test` (빈 DB) | 8100 | 자동화 테스트와, 데이터를 지워도 되는 수동 검증 |

```bash
./mvnw -pl app-mvc spring-boot:run -Dspring-boot.run.profiles=dev        # :8000
./mvnw -pl app-mvc spring-boot:run -Dspring-boot.run.profiles=test       # :8100  market_test
cd front && npm run dev                                                  # :3000
```

`dev` 와 `test` 는 포트가 달라 동시에 띄울 수 있다.

**`dev` 는 공유 DB 를 본다.** 실제 회원·글·일정·신청서가 들어 있고 백업이 없다. 데이터를
지우는 검증은 `test` 로 띄운다 — 그것이 `market_test` 를 따로 둔 이유다.

`market_test` 가 없으면 한 번 만든다(스키마는 Flyway 가 세운다):

```bash
docker exec woobeee-db psql -U root -d postgres -c "CREATE DATABASE market_test OWNER root;"
```

바인딩 파라미터 TRACE 로그는 `dev` 에만 있다 — 배포에서 켜면 이메일·토큰 같은 값이 그대로
로그 파일에 남는다.

### Docker 이미지 · compose

```bash
docker build -f app-mvc/Dockerfile -t woobeee/app-mvc:latest .   # 빌드 컨텍스트 = 레포 루트
docker build -t woobeee/front:latest front/                      # 컨텍스트 = front/
docker build -t woobeee/ai-worker:latest ai-worker/              # 컨텍스트 = ai-worker/ (AI 요청하기 워커, ADR-005)
```

`front/Dockerfile` 의 `MVC_ORIGIN`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID` 는
**빌드 인자**라 이미지에 구워진다 — 바꾸려면 다시 빌드한다. `.docker-compose/docker-compose.yml` 에
`app-mvc`·`front` 서비스가 있고 컨테이너 프로파일은 `prod-docker`
(`app-mvc/src/main/resources/application-prod-docker.yml`)다.

**경고: compose 의 `app-mvc` 서비스는 실제 `market` DB 에 붙고 부팅 때 Flyway 가 돈다.** 띄우는 것만으로
밀린 마이그레이션이 그 DB 에 적용된다 — 특히 되돌릴 수 없는 컬럼·테이블 삭제 마이그레이션이 그렇다 — `V26__drop_game.sql`
(`game_results` 등 삭제), `V27__pokemon_drop_fx.sql`(`pokemon_rounds`·`pokemon_orders` 의 환율·확정 원화 컬럼 삭제). 아래
"DB 를 바꾸는 것은 먼저 확인받는다" 규칙의 대상이다. 새 테이블만 더하는 `V28__ai_requests.sql`(`ai_requests`·`ai_outbox`·`ai_request_actions`)도 같다 — 되돌릴 수는 있지만 적용 전에 SQL 을 보여주고 승인받는다.

`-pl <module>` 단독 실행은 `com.woobeee:core` 가 로컬 리포에 설치돼 있어야 한다. 클린 클론에서는
먼저 `./mvnw -pl core -am install -DskipTests` 를 한 번 실행한다.

### 필요한 환경변수

미설정 시 `application.yaml` 기본값을 쓴다.

- app-mvc: `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- ai (선택): app-mvc·`ai-worker` 의 `AI_WORKER_SECRET`, app-mvc 의 `KAFKA_BOOTSTRAP_SERVERS`(기본 `localhost:8011`), `ai-worker` 의 `CLAUDE_CODE_OAUTH_TOKEN`(구독 로그인 — `claude setup-token` 으로 만든다. 대안 `ANTHROPIC_API_KEY`). 값은 `.docker-compose/.env`(git 제외)에만 둔다
- app-mvc (pokemon, 선택): `POKEMON_MANAGER_MEMBER_IDS`(기본 `1`), `POKEMON_RATE_URL_TEMPLATE`, `POKEMON_RATE_CACHE_TTL`, `POKEMON_CURRENCIES`(기본 `INR,USD,JPY`)
- front: `MVC_ORIGIN`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (`front/.env.local.example` 참조)

## API 엔드포인트

전체 엔드포인트와 접근 권한(공개/로그인/ADMIN)은 **`docs/api/README.md`** 가 단일 정리본이다.
엔드포인트나 권한이 바뀌면 그 문서를 함께 갱신한다.

| 앱 | 도메인 | 베이스 경로 | 비고 |
| --- | --- | --- | --- |
| app-mvc | auth | `/api/auth` | `signup`, `login`, `callback-google`, `access-tokens`, `refresh-tokens`, `me`, `me/profile-image*` |
| app-mvc | blog | `/api/back/posts`, `/api/back/tags`, `/api/back/comments`, `/api/back/likes`, `/api/back/categories` | 게시글/태그/댓글/좋아요/카테고리 — 게시글·카테고리 쓰기는 `ROLE_ADMIN` 전용, 태그는 글쓰기 안에서만 생성 |
| app-mvc | schedule | `/api/back/schedule` | 일정 트리/프로젝트/마일스톤/할 일/할 일 이슈 — 전부 로그인 필수, 본인 것만 |
| app-mvc | ai | `/api/back/ai`, `/internal/ai` | AI 요청하기 — 글·이미지를 올리면 별도 컨테이너 `ai-worker`(Claude Code + MCP)가 Kafka 로 받아 상품·일정을 **제안**하고, 승인하면 요청자 권한으로 등록한다(ADR-005, `docs/ai/PRD.md`). 사용자 API 는 로그인 필수·본인만, `/internal/ai` 는 워커 시크릿 전용 |
| app-mvc | pokemon | `/api/back/pokemon` | 포켓코인 공동구매 — 주최자가 **차수**(1차·2차·3차)를 열고 회원들이 신청한다. `/pokemon/{handle}/{sequence}` 로 주최자별 URL. 통화·계좌·**상품표**를 차수가 든다. 원화는 저장하지 않고 하루 한 번(09:10 KST) 갱신되는 환율로 매번 계산한 **예상 금액**이다(환차손익 없음). 조회는 공개, 신청·댓글·개설은 **로그인 필수** |

## 안전 수칙

### DB 를 바꾸는 것은 먼저 확인받는다

`localhost:9432/market` 은 **개발용이자 실사용 DB** 다. 회원·글·일정·신청서가 실제로 들어 있고,
백업도 WAL 아카이빙도 없다. 지운 행은 되돌릴 수 없다.

다음은 **실행 전에 반드시** 문장 그대로와 영향 범위를 보여주고 사용자의 승인을 받는다.
"내가 만든 테스트 데이터를 치우는 것뿐" 은 예외가 아니다 — 그 판단이 틀렸을 때 잃는 쪽이
사용자의 데이터다.

| 대상 | 예 |
| --- | --- |
| 행을 지우는 것 | `DELETE`, `TRUNCATE` |
| 행을 덮어쓰는 것 | `UPDATE` (특히 `WHERE` 없는 것) |
| 스키마를 바꾸는 것 | `ALTER`, `DROP`, 새 Flyway 마이그레이션의 **적용** |

지우기 전에 무엇이 지워지는지 먼저 센다. 범위를 확인하지 않은 `DELETE` 는 금지다:

```bash
docker exec woobeee-db psql -U root -d market -c \
  "SELECT count(*) FROM pokemon_orders WHERE applicant_name LIKE '\_\_test\_\_%';"
```

- **`WHERE` 없는 `DELETE`/`UPDATE` 는 쓰지 않는다.** 테스트 데이터는 지울 때 골라낼 수 있도록
  처음부터 표시를 달아 만든다(예: 신청자명 `__test__` 접두사).
- **앱을 띄우는 것도 스키마 변경이다.** `spring-boot:run` 과 `java -jar` 는 부팅 때 밀린
  Flyway 마이그레이션을 공유 DB 에 **자동으로 적용**한다. 새 `V<n>__*.sql` 을 쓴 뒤 처음 띄울
  때는 어떤 마이그레이션이 돌지 먼저 알린다.
- **적용된 마이그레이션 파일은 고치지 않는다.** Flyway 체크섬이 깨져 다음 부팅이 실패한다.
  바꿀 것이 있으면 새 버전을 추가한다(그래서 V15 의 제약을 V18 에서 풀었다).

> 2026-09-29 에 실제로 잃었다. pokemon 기능을 검증하면서 내 테스트 행만 치운다는 생각으로
> `DELETE FROM pokemon_orders` 를 여러 번 돌렸는데, 그 문장은 테이블 전체를 비운다. 사용자가
> 화면에서 넣은 신청서가 함께 사라졌고 복구하지 못했다. 그때도 이 문서에는 "destructive SQL
> 은 승인 없이 실행하지 않는다" 가 있었다 — 규칙이 없어서가 아니라 "내 테스트 데이터니까"
> 로 넘겨서 벌어진 일이다. 그래서 위 표를 예외 없이 적용한다.

### 그 밖

- 위험 명령(`rm -rf`, `git reset --hard`, `git push --force`)은 사용자의 명시적 승인 없이 실행하지 않는다.
- 구현/검증 중 테스트·빌드가 실패하면 원인을 요약하고 **수정 지속 / 부분 롤백 / 전체 롤백** 중 어디로 갈지 사용자에게 확인한다. 승인 없이 실패한 변경을 임의로 되돌리지 않는다.
- 전신 리포 `/Users/administrator/Documents/projects/art-market-place` 는 **수정하지 않는다**.

## 알려진 후속 과제

| 항목 | 내용 |
| --- | --- |
| front 잔존 페이지·API | `app/products`, `app/cart`, `app/chat` 은 폐기된 백엔드를 호출한다. 페이지뿐 아니라 `lib/api.ts` 의 `/api/products*`·`/api/buyers/{id}/carts/*` 클라이언트 여덟 개도 살아 있고 `next.config.mjs` 는 그 경로를 rewrite 하지 않는다. `app/products/[productId]/page.tsx` 에는 `/cart` 링크도 남아 있다 |
| front AC 미작성 | `docs/front/PRD.md` 에 `## 인수 기준` 표가 없다. 300개로 이 레포에서 가장 큰 테스트 스위트인데 AC ID 를 참조하는 테스트가 하나도 없어, 위 "테스트는 PRD의 인수 기준에서 도출한다" 규칙이 가장 필요한 도메인에서 지켜지지 않는다 |
| 프론트 컴포넌트 무검증 | vitest 가 node 환경이고 jsdom 이 없다. 판단은 전부 `lib/` 로 옮겨 뒀지만 fetch 이펙트·타이머·이펙트 정리(cleanup)가 **돌긴 하는지**는 아무도 확인하지 않는다 — `return () => …` 을 통째로 지워도 300개가 그대로 통과한다 |
| pokemon 서버 테스트 얇음 | `front/lib/pokemon.test.ts` · `ExchangeRateServiceTest` · `PokemonServiceImplTest` · `PokemonErrorCodeTest` · `PokemonPropertiesTest` 가 있다. 예상 금액(AC-04·05)·환율 갱신/폴백(AC-06)은 고정됐지만 POKEMON-AC-20·21 — 상품 관리 권한, 내리기 vs 지우기 — 은 **코드로만** 지켜진다. AC-09 는 명단 판정만 테스트가 있고, 그 판정이 실제로 `PATCH /status` 를 막는지는 확인하지 않는다 |
| 접근 로그 필터 순서가 계약 | `RequestAccessLogFilter` 는 `@Order(HIGHEST_PRECEDENCE)` 로 가장 바깥에 있어야 401/403 으로 잘린 요청까지 남는다. 대신 바깥이라 토큰 필터가 **아래로** 넘기는 래퍼의 `loginId` 헤더가 안 보이므로, 신원은 `AccessTokenLoginIdHeaderFilter.LOGIN_ID_ATTRIBUTE`(원본 요청 attribute)로 받는다. 둘 중 하나만 바꾸면 로그인한 요청이 전부 `비회원` 으로 찍히거나 거절된 요청이 사라진다 |
| CI 없음 | 레포에 워크플로가 없다. 백엔드·프론트 테스트를 PR 마다 강제하는 것이 없다. `front/lib/game-errors.ts`(pokemon 화면이 `describeGameApiError` 로 쓴다)와 `front/lib/errors/error-messages.ts` 의 코드 키 지도도 서버 에러 코드와 짝을 맞추는 테스트가 없다 |
| app-mvc 에 catch-all advice 없음 | `AuthRestControllerAdvice` 가 `basePackages="com.woobeee.mvc.auth"` 로 좁혀져 있어 네 부류가 `ApiResponse` 봉투 밖으로 나간다: auth 서비스의 `ResponseStatusException` 12곳, `CustomInternalServerException`, blog 컨트롤러 전체의 빈 검증, `DELETE /api/auth/me/profile-image` 의 204. 프론트는 전부 "예기치 못한 오류" 한 문장으로 받는다. 게다가 app-mvc 는 `header.message` 에 영어 문장을 넣는데 프론트는 그 자리를 **코드 키**로 읽는다 — 반드시 어긋난다.pokemon 은 자체 에러 코드(`PokemonErrorCode`)로 봉투를 지키니 그 모양을 옮기면 된다 |
| MinIO presign | presigned URL 의 host 는 서명에 포함되므로 만든 뒤 문자열로 못 고친다. app-mvc 는 `storage.s3.endpoint`(서버→MinIO)와 `storage.s3.public-endpoint`(브라우저용, 프로덕션 `https://image.woobeee.com`)로 쪼개 해결했다. **글 본문 이미지와 프로필 이미지 모두** 이 presigned URL 로 브라우저가 MinIO 에 직접 붙고 **버킷은 비공개로 남는다**(서명이 접근을 허가한다). 서명은 AWS SDK 가 아니라 `PresignedUrlFactory` 가 만든다 — SDK 는 `X-Amz-Date` 를 항상 "지금"으로 넣고 클록 주입 수단이 없어 URL 이 요청마다 달라지고, CDN 이 쿼리스트링까지 캐시 키에 넣으므로 방문자마다 다른 오브젝트가 된다(실측: 새 서명은 매번 MISS). 그래서 **서명 시각을 1시간 단위로 내려** 같은 시간대면 동일한 URL 이 나오게 하고, 만료 절벽을 피하려 TTL 은 버킷보다 길게(기본 24시간) 둔다 — 최악의 남은 유효기간 23시간.  (기보 뷰어를 쓰던 app-webflux 는 제거돼 남은 항목이 없다) |
| 고아 오브젝트 정리 | `profiles/` 의 고아 발생 경로는 사라졌다 — presigned 발급이 없어졌고 업로드가 앱을 거치며 즉시 컬럼에 등록되기 때문이다. 남은 경로는 **교체 시 이전 오브젝트 삭제 실패**뿐이다(의도적으로 조용히 넘긴다: 삭제가 프로필을 깨뜨리면 안 된다). 글 첨부(`{postId}/`)는 글이 지워져도 오브젝트가 남으므로 여전히 lifecycle 정책이 필요하다 |
| front 취약점 | 이관한 `package-lock.json` 기준 `npm audit` 17건(high 13, moderate 4). 전신 리포에서 그대로 넘어온 것 |
| blog AC 일부 미작성 | `docs/blog/PRD.md` 의 BLOG-AC-05·06(댓글·좋아요)은 아직 테스트가 없다. 목록·태그(AC-01~04, 18~22)는 `PostRepositoryTest`(실 Postgres)·`PostServiceImplTest` 로 고정됐다 |
| 이관 문서 잔여 언급 | `docs/ARCHITECTURE.md` 에 product/cart 절이 크게 남아 있고, 존재하지 않는 `docs/cart/` 로 가는 ADR 링크 두 개와 존재하지 않는 `.codex/settings.json` 참조, 그리고 클래스명과 다른 blog 엔티티 이름(`Post` vs `Posts`)이 있다. `docs/<domain>/adr/` 은 `auth`·`blog` 에만 있다 |
| 자잘한 미고정 | `auth-redirect.ts` 의 `startsWith("//")` 는 정규화 검사에 가려져 지워도 통과한다(중복일 뿐, 정규화 검사를 지워도 된다는 뜻이 아니다). `getBrowserLocale` 을 아무도 import 하지 않아 `en` 메시지 블록 전체가 런타임에 도달 불가다. `ErrorPayload.status` 는 채워지지만 읽는 곳이 없다. |
| 5B 잔여 (표시상) | `Header` 가 클라이언트 전용 `mounted` 로 렌더를 막아 서버 HTML 에는 없다 — 이제 전 라우트에서 하이드레이션 후 튀어나온다. `/blog` 로 클라이언트 이동할 때 한 프레임 동안 사이드바 토글 없이 그려진다. 죽은 `/products`·`/cart` 가 자체 sticky 헤더를 그려 전역 헤더 뒤로 숨는다. 인증 화면 넷이 `min-h-screen` 이라 64px 헤더 아래에서 살짝 어긋난다 |
| Kafka 연동 범위 | `ai` 요청 전달 전용(ADR-005). 다른 도메인은 연동하지 않는다 — 쓰려면 토픽 설계와 새 ADR 부터 |
| 하네스 재설계 | `art-market-place`의 `amp-backend-feature` 하네스는 art-marketplace 도메인 전제여서 이관하지 않았다. 필요 시 blog/pokemon 기준으로 새로 구성 |

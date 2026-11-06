# ARCHITECTURE

> **2026-07-31 재구성:** 단일 모듈 모놀리스(`art-market-place`)에서 Maven 멀티모듈
> (`woobeee`)로 전환했다. product/cart는 폐기했다. 이후 game(WebFlux, `app-webflux`)을 추가했다가
> 제거해 현재 백엔드는 `app-mvc` 하나다. 아래 모듈 구조가 우선하며, 이후 절의 도메인 설명은
> auth/blog/schedule/pokemon에 대해서만 유효하다.

## 모듈 구조

```text
            Next.js (front :3000)
        /api/auth/*   /api/back/*
                  │
            [app-mvc :8000]
            Tomcat / JPA
                  │
                core
   (ApiResponse · 토큰 계약 · Redis 토큰스토어)
                  │
     ┌────────────┴────────────┐
PostgreSQL :9432          Redis :9379
 (Flyway가 스키마 관리)     (토큰)

app-mvc → MinIO :9000 (S3Client, 블로킹) — 프로필/게시글 이미지
(core 를 거치지 않고 직접 붙는다 — core 는 웹 스택 무의존이라 스토리지 클라이언트도 두지 않는다)
```

| 모듈 | 스택 | 책임 | 의존 |
| --- | --- | --- | --- |
| `core` | 순수 라이브러리 (웹 스택 무의존) | `ApiResponse`, 토큰 계약(`AuthTokenType`/`TokenMetadata`), `RedisTokenStore` | — |
| `app-mvc` | Boot + `starter-webmvc` + JPA | auth(토큰 발급·로그인·OAuth) + blog + schedule + pokemon, Flyway 소유 | core |
| `front` | Next.js 14 | rewrites로 `app-mvc` 프록시. 화면 전체(블로그·일정·공동구매·마이페이지) | — |

- `core` 는 공유 계약 모듈로 남아 있지만 지금은 `app-mvc` 만 소비한다.
- 스키마: Flyway가 단일 소스(`app-mvc/src/main/resources/db/migration/`). JPA는 `validate`
  전용이다. `V26__drop_game.sql` 이 게임 테이블(`game_results`, `game_result_participants`)과
  `members.game_money` 컬럼을 삭제했다 — 되돌릴 수 없다. `gameMoney` 필드도 함께 제거했다.
- 오브젝트 스토리지: app-mvc 가 블로킹 `S3Client` 로 MinIO/S3 에 직접 붙는다(프로필 이미지,
  게시글 이미지).
- 배포: `app-mvc/Dockerfile`, `front/Dockerfile`, compose 서비스 `app-mvc` · `front`.
  compose 의 `app-mvc` 는 실제 `market` DB 에 붙어 부팅 때 Flyway 를 돌리므로, 띄우면 밀린
  마이그레이션(V25 포함)이 적용된다.
  백엔드는 `prod-docker` 프로파일(`app-mvc/src/main/resources/application-prod-docker.yml`)로 뜬다.

## 이전 구조 (art-market-place 기준 — 이하 절은 auth/blog 흐름 참고용)

`art-market-place`는 Spring Boot 백엔드와 Next.js 프론트엔드를 같은 레포지토리에 둔 단일 저장소 구조다.

## 상위 구조

```text
art-market-place
|-- src/                  Spring Boot 백엔드
|-- front/                Next.js 프론트엔드
|-- docs/                 제품/아키텍처/기술결정 문서
|-- .claude/              project 범위 claude 스킬, 설정
|-- .docker-compose/      로컬 PostgreSQL, Redis, Kafka, MinIO 구성
`-- pom.xml               백엔드 Maven 설정
```

## 백엔드 모듈

### `auth`

회원가입, 로그인, Google OAuth callback, 토큰 발급/재발급, 회원 프로필과 프로필 이미지를 담당한다.

- Controller: `AuthController`, `TokenGenerateController`, `MemberProfileImageController`
- Service: `AuthService`, `TokenService`, `MemberProfileImageService`, Google OAuth 관련 client/store
- Entity: `Member`(단일 회원, `members`), `Address`
- Repository: `MemberRepository`
- Token: `TokenGenerator`, `TokenStore`, Redis 기반 구현. 역할은 `ROLE_MEMBER` 하나다.
- Storage: 프로필 이미지는 `_common/storage`의 `S3Client`/`S3Presigner`를 주입받아 presigned PUT/GET으로 다룬다.

### `blog`

게시글, 카테고리, 댓글, 좋아요 기능을 담당한다.

- Controller: `PostController`, `CategoryController`, `CommentController`, `LikeController`
- Service: 각 도메인별 interface/implementation 분리
- Repository: 단순 CRUD는 Spring Data JPA, 검색/집계 조회는 로우 쿼리(Native SQL — `PostQueryRepositoryImpl` 은 `EntityManager` 네이티브 쿼리, QueryDSL 은 2026-09-09 제거)
- Entity: `Posts`, `Categories`, `Comments`, `Likes`
- Support: 페이징, Redis 지원, 업로드 진행 스트림

> `product`/`cart` 도메인은 멀티모듈 전환 때 폐기했다(위 `## 모듈 구조` 참고). 전신
> `art-market-place`에만 남아 있고 이 레포에는 코드도 문서도 없다.

### `schedule`

프로젝트/마일스톤/할 일로 구성된 일정 트리를 담당한다. 컨트롤러 하나로 트리 조회와
프로젝트/마일스톤/할 일의 생성·수정·삭제를 받는다.

- Controller: `ScheduleController` 단일 진입점 (`/api/back/schedule/**`)
- Package: `com.woobeee.mvc.schedule` — `controller`/`service`/`repository`/`entity`/`api`/`exception`
- Entity: `Projects`, `Milestones`, `Tasks` (`projects`/`milestones`/`tasks`, V8)
- 무결성은 FK 가 아니라 서비스 계층에서 강제한다: 소유권 검증(본인이 아니면 404 로 위장해
  존재 자체를 숨긴다), 프로젝트를 넘나드는 이동 금지, 깊이 5단계 제한, 순환 참조 방지, 날짜
  범위, `#RRGGBB` 색상 형식.
- 삭제는 명시적 캐스케이드다: 프로젝트 삭제가 하위 마일스톤·할 일을, 마일스톤 삭제가 하위
  할 일을 서비스 코드에서 함께 지운다(DB 레벨 `ON DELETE CASCADE` 없음).
- 조회는 `/tree` 하나뿐이다 — 프로젝트/마일스톤/할 일을 배치 쿼리 3회로 모아 N+1 없이 트리를
  구성한다.
- 오류: `ScheduleControllerAdvice` 가 `schedule_*` 코드 키로 `ApiResponse` 봉투에 담는다. 코드
  집합은 `front/lib/errors/error-messages.ts`(ko/en) 와 짝을 이루고 `ScheduleErrorCodeTest` 가
  양쪽의 지도 불일치를 잡는다.
- 인수 기준: `docs/schedule/PRD.md` 의 SCHEDULE-AC-01~20.

### `ai`

글·이미지를 받아 Claude Code 가 공동구매 상품·일정을 **제안**하고 사용자가 승인하면 등록한다
(`docs/ai/PRD.md`, ADR-005). 요청과 `ai_outbox` 를 한 트랜잭션에 저장하고, 발행기가 Kafka `ai.request.v1` 로
보낸다. 별도 컨테이너 `ai-worker`(Node/TS)가 한 번에 하나씩 소비해 app-mvc 내부 API(`/internal/ai/**`)로
claim → `claude -p`(MCP 서버 `woobeee`) → 제안 기록 → complete 한다. 반영은 승인 시 `AiActionApplier` 가
요청자 권한으로 `PokemonService`·`ScheduleService` 를 호출해서만 일어난다.

### `_common`

공통 요청 처리와 필터를 둔다.

- `AccessTokenLoginIdHeaderFilter`: access token 기반 loginId 헤더 주입
- `MutableHttpServletRequest`: 요청 헤더 조작을 위한 wrapper
- `CorsConfig`: `/api/**` 요청에 대해 로컬 프론트 개발 서버와 운영 도메인의 CORS를 허용

## 데이터 저장소

- PostgreSQL: app-mvc(JPA)의 영속성 저장소
- Redis: 토큰, OAuth state, 세션/캐시성 데이터
- S3/MinIO: auth 프로필 이미지, blog 게시글 이미지(app-mvc)

## 쿼리 구현 규칙

- 단순 조회는 Spring Data JPA 파생 메서드로 유지한다.
- 그 외 모든 커스텀 조회(동적 조건, 검색, 집계, 조인/서브쿼리, 목록)는 로우 쿼리(Native SQL)로 작성한다. QueryDSL은 사용하지 않는다(의존 제거됨).
- 로우 쿼리는 N+1을 해결한 형태(조인 일괄 조회 또는 식별자 배치 IN 조회)로 작성하고, 값은 바인딩 파라미터로만 주입한다.
- 상세 정책: `docs/_global/adr/ADR-001-postgresql.md`.

## 주요 흐름

### 인증 흐름

1. 클라이언트가 회원가입 또는 로그인 authorization 생성을 요청한다.
2. 서버가 Google authorization URL과 state를 발급한다.
3. Google callback에서 code/state를 검증한다.
4. 서버가 회원 정보를 확인하고 access/refresh token을 발급한다.
5. refresh token은 device, IP 정보와 함께 저장소에서 검증한다.

### 블로그 흐름

1. 게시글은 카테고리, 검색어, 페이징 조건으로 조회한다.
2. 상세 조회는 locale과 로그인 정보를 반영한다.
3. 댓글은 댓글/대댓글 구조를 지원한다.
4. 좋아요는 로그인 사용자 기준으로 등록/취소한다.

## 검증과 안전장치

- `spring.jpa.properties.hibernate.hbm2ddl.auto=validate`를 기본으로 사용한다.
- 기본 검증 명령은 `CLAUDE.md`의 "빌드 · 실행 · 검증" 절이 단일 출처다
  (`./mvnw -pl core,app-mvc -am test`, `cd front && npm run build`).
- `SchemaValidationTest`는 JPA 매핑과 실제 PostgreSQL 스키마 불일치를 감지하기 위한 전용 테스트다.
- 엔티티 변경은 DB 스키마 변경으로 간주하며, 문서와 마이그레이션 계획을 함께 갱신해야 한다.

# ARCHITECTURE

> **2026-07-31 재구성:** 단일 모듈 모놀리스(`art-market-place`)에서 Maven 멀티모듈
> (`woobeee`)로 전환했다. product/cart는 폐기했고 game(WebFlux)을 추가했다. 아래 모듈 구조가
> 우선하며, 이후 절의 도메인 설명은 auth/blog에 대해서만 유효하다.

## 모듈 구조

```text
                    Next.js (front :3000)
        /api/auth/* /api/back/*      /api/game/*        /ws/game
                    │                     │                 │
                    │                     │       (rewrites 우회 — 직접 연결)
               [app-mvc :8000]     [app-webflux :8001] ◄────┘
               Tomcat / JPA         Netty / R2DBC
                    │                     │
                    └──────── core ───────┘
              (ApiResponse · 토큰 계약 · Redis 토큰스토어)
                    │                     │
            ┌───────┴─────────────────────┴───────┐
       PostgreSQL :9432 (공유)          Redis :9379 (공유 토큰)
            └──── Flyway가 스키마 단일 관리 (app-mvc 소유) ────┘

        app-mvc     → MinIO :9000 (S3Client, 블로킹)      — 프로필/게시글 이미지
        app-webflux → MinIO :9000 (S3AsyncClient, 논블로킹) — 기보(replay) 저장
        (둘 다 core 를 거치지 않고 각자 직접 붙는다 — core 는 웹 스택 무의존이라 스토리지
        클라이언트도 두지 않는다)
```

| 모듈 | 스택 | 책임 | 의존 |
| --- | --- | --- | --- |
| `core` | 순수 라이브러리 (웹 스택 무의존) | `ApiResponse`, 토큰 계약(`AuthTokenType`/`TokenMetadata`), `RedisTokenStore` | — |
| `app-mvc` | Boot + `starter-webmvc` + JPA | auth(토큰 발급·로그인·OAuth) + blog, Flyway 소유 | core |
| `app-webflux` | Boot + `starter-webflux` + R2DBC | game(방·오목·장애물피하기·기보), Redis 토큰 **검증만** | core |
| `front` | Next.js 14 | rewrites로 두 백엔드 프록시. 화면 전체(블로그·게임·마이페이지) | — |

- 논리적으로 하나의 앱(회원·DB·Redis 공유), 물리적으로 Boot 프로세스 2개(Netty와 Tomcat은 한
  JVM에 공존 불가).
- 토큰 공유: app-mvc(auth)가 발급 → Redis에 `auth:token:access:<token>` 해시로 저장 →
  app-webflux가 `ReactiveTokenVerifier`로 같은 키를 직접 읽어 검증. 앱 간 HTTP 호출 없음.
- 스키마: Flyway가 단일 소스(`app-mvc/src/main/resources/db/migration/`). JPA는 `validate`
  전용이고 app-webflux는 `flyway.enabled=false`.
- **`members` 는 app-mvc(JPA)와 app-webflux(R2DBC)가 공유하는 첫 테이블이다. 쓰기 소유권은
  app-mvc 단독이고 app-webflux 는 게임 참가자 닉네임을 읽기만 한다.**
- 오브젝트 스토리지: 두 앱 모두 MinIO/S3 를 쓰지만 core 를 거치지 않고 각자 붙는다. app-mvc 는
  블로킹 `S3Client`(프로필 이미지, 게시글 이미지), app-webflux 는 논블로킹 `S3AsyncClient`
  (기보 업로드/presigned 다운로드)를 쓴다.

### 단일 오리진 규칙의 예외 하나 — WebSocket

프론트엔드는 `/ws/game` 에 WebFlux 오리진으로 **직접** 붙는다. Next.js rewrites 는 HTTP 전용이라
WebSocket 업그레이드를 프록시하지 못하기 때문이다. 그래서 게임 화면만 "rewrites 로 두 백엔드를
단일 오리진화한다" 는 규칙에서 벗어나며, 브라우저가 볼 오리진을 `NEXT_PUBLIC_WS_BASE_URL` 로
따로 알려줘야 한다. 자세한 내용은 [`FRONTEND.md`](FRONTEND.md) 의 WebSocket 절.

### 게임 흐름 (요약)

1. 회원이 `POST /api/game/rooms` 로 방을 만들고 `roomId` + `inviteCode` 를 받는다.
2. 초대 링크를 연 방문자는 `GET /api/game/rooms/{roomId}?invite=` 로 방을 확인한다. 회원이면
   액세스 토큰으로, 비회원이면 `POST .../guest-tokens` 로 받은 게스트 토큰으로 들어간다.
3. 화면이 `/ws/game` 에 붙어 첫 메시지로 `JOIN{roomId, inviteCode, token}` 을 보낸다. 이후
   방 상태·게임 이벤트는 전부 이 소켓으로 오간다. 진행 중인 방으로 재접속하면 `GAME_SNAPSHOT`
   으로 현재 판을 통째로 받는다.
4. 게임이 끝나면 `game_results` + `game_result_participants` 에 결과를 남기고 기보를 MinIO 에
   올린다. 마이페이지가 `GET /api/game/me/results` 로 전적을, `GET /api/game/results/{id}/replay`
   로 presigned 기보 URL 을 가져와 다시 그린다.

규칙·계약·인수 기준은 [`game/PRD.md`](game/PRD.md) 가 단일 출처다.

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
- Repository: 단순 CRUD는 Spring Data JPA, 검색/집계 조회는 로우 쿼리(Native SQL, 기존 QueryDSL fragment는 마이그레이션 대상)
- Entity: `Posts`, `Categories`, `Comments`, `Likes`
- Support: 페이징, Redis 지원, 업로드 진행 스트림

> `product`/`cart` 도메인은 멀티모듈 전환 때 폐기했다(위 `## 모듈 구조` 참고). 전신
> `art-market-place`에만 남아 있고 이 레포에는 코드도 문서도 없다.

### `_common`

공통 요청 처리와 필터를 둔다.

- `AccessTokenLoginIdHeaderFilter`: access token 기반 loginId 헤더 주입
- `MutableHttpServletRequest`: 요청 헤더 조작을 위한 wrapper
- `CorsConfig`: `/api/**` 요청에 대해 로컬 프론트 개발 서버와 운영 도메인의 CORS를 허용
- `QuerydslConfig`: 기존 QueryDSL 조회용 `JPAQueryFactory` 제공. 로우 쿼리 마이그레이션 완료 후 제거 예정(ADR-001).

## 데이터 저장소

- PostgreSQL: app-mvc(JPA)의 영속성 저장소이자 app-webflux(R2DBC)와 공유하는 `members` 테이블의 소유자
- Redis: 토큰, OAuth state, 세션/캐시성 데이터
- S3/MinIO: auth 프로필 이미지, blog 게시글 이미지(app-mvc), game 기보 파일(app-webflux)

## 쿼리 구현 규칙

- 단순 조회는 Spring Data JPA 파생 메서드로 유지한다.
- 그 외 모든 커스텀 조회(동적 조건, 검색, 집계, 조인/서브쿼리, 목록)는 로우 쿼리(Native SQL)로 작성한다. QueryDSL은 더 이상 사용하지 않으며 기존 fragment는 마이그레이션 대상이다.
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
  (`./mvnw -pl core,app-mvc,app-webflux -am test`, `cd front && npm run build`).
- `SchemaValidationTest`는 JPA 매핑과 실제 PostgreSQL 스키마 불일치를 감지하기 위한 전용 테스트다.
- 엔티티 변경은 DB 스키마 변경으로 간주하며, 문서와 마이그레이션 계획을 함께 갱신해야 한다.
